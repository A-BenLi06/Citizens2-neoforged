package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MemoryDataKeyTransactionTest {
    @Test void failedOuterWritePreservesTheCompleteDocument() {
        MemoryDataKey root = document();
        Map<String, Object> before = root.getValuesDeep();
        assertThrows(IllegalStateException.class, () -> root.getRelative("records.0").writeAtomically(key -> {
            key.setString("name", "partial"); key.getFromRoot("global.provider").setString("value", "partial global");
            throw new IllegalStateException("synthetic failure");
        }));
        assertEquals(before, root.getValuesDeep());
    }

    @Test void caughtInnerFailurePreservesParentChangesAndAllowsRetry() {
        MemoryDataKey root = document();
        root.writeAtomically(outer -> {
            outer.setString("outer", "retained");
            Map<String, Object> before = outer.getValuesDeep();
            assertThrows(IllegalStateException.class, () -> outer.getRelative("records.0").writeAtomically(inner -> {
                inner.setString("name", "partial"); inner.getFromRoot("global.provider").setString("value", "partial global");
                throw new IllegalStateException("synthetic failure");
            }));
            assertEquals(before, outer.getValuesDeep());
            outer.getRelative("records.0").writeAtomically(inner -> inner.setString("name", "retried"));
            assertEquals("retried", outer.getString("records.0.name"));
            assertEquals("old", root.getString("records.0.name"));
        });
        assertEquals("retried", root.getString("records.0.name"));
        assertEquals("retained", root.getString("outer"));
    }

    @Test void anOuterFailureDiscardsSuccessfulInnerWrites() {
        MemoryDataKey root = document();
        Map<String, Object> before = root.getValuesDeep();
        assertThrows(IllegalStateException.class, () -> root.writeAtomically(outer -> {
            outer.getRelative("records.0").writeAtomically(inner -> inner.setString("name", "unpublished"));
            throw new IllegalStateException("later writer failed");
        }));
        assertEquals(before, root.getValuesDeep());
    }

    @Test void nestedWritesKeepLiteralPathsAndAbsoluteNamespaces() {
        MemoryDataKey root = new MemoryDataKey();
        root.setMap("records", Map.of("literal.key", Map.of("name", "old")));
        DataKey literal = root.getRelative("records").getSubKeys().iterator().next();
        literal.writeAtomically(outer -> outer.writeAtomically(inner -> {
            assertEquals(literal.name(), inner.name()); assertEquals(literal.getPath(), inner.getPath());
            inner.setString("name", "updated"); inner.getFromRoot("global.provider").setString("value", "shared");
        }));
        assertEquals("updated", literal.getString("name"));
        assertEquals("shared", root.getString("global.provider.value"));
        assertNull(root.getRaw("records.literal"));
    }

    @Test @SuppressWarnings("unchecked")
    void retainedInnerAndOuterKeysCannotMutatePublishedValues() {
        MemoryDataKey root = document();
        DataKey[] retained = new DataKey[2];
        byte[] bytes = { 1, 2 };
        List<Object> payload = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("binary", bytes))));
        root.writeAtomically(outer -> {
            retained[0] = outer;
            outer.getRelative("records.0").writeAtomically(inner -> {
                retained[1] = inner; inner.setRaw("payload", payload); inner.setString("name", "published");
            });
            retained[1].setString("name", "late inner edit"); bytes[0] = 9;
            assertEquals("published", outer.getString("records.0.name"));
            assertArrayEquals(new byte[] { 1, 2 }, (byte[]) ((Map<?, ?>) ((List<?>) outer.getRaw("records.0.payload")).getFirst()).get("binary"));
        });
        retained[0].setString("records.0.name", "late outer edit");
        retained[1].getFromRoot("global.provider").setString("value", "late global edit");
        ((List<Object>) retained[0].getRaw("records.0.payload")).clear();
        assertEquals("published", root.getString("records.0.name"));
        assertEquals("shared", root.getString("global.provider.value"));
        assertArrayEquals(new byte[] { 1, 2 }, (byte[]) ((Map<?, ?>) ((List<?>) root.getRaw("records.0.payload")).getFirst()).get("binary"));
    }

    @Test @SuppressWarnings("unchecked")
    void rawMapListAndArrayMutationsCommitAndRollback() {
        MemoryDataKey root = document();
        root.setRaw("records.0.sequence", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("value", "old")))));
        root.setRaw("records.0.binary", new byte[] { 1, 2 });
        root.writeAtomically(outer -> {
            assertThrows(IllegalStateException.class, () -> outer.getRelative("records.0").writeAtomically(inner -> {
                ((Map<String, Object>) inner.getRaw("")).put("name", "failed");
                ((Map<String, Object>) ((List<?>) inner.getRaw("sequence")).getFirst()).put("value", "failed");
                ((byte[]) inner.getRaw("binary"))[0] = 9;
                throw new IllegalStateException("failed raw edit");
            }));
            assertEquals("old", outer.getString("records.0.name"));
            assertEquals("old", ((Map<?, ?>) ((List<?>) outer.getRaw("records.0.sequence")).getFirst()).get("value"));
            assertArrayEquals(new byte[] { 1, 2 }, (byte[]) outer.getRaw("records.0.binary"));
            outer.getRelative("records.0").writeAtomically(inner -> {
                ((Map<String, Object>) inner.getRaw("")).put("name", "raw edit");
                ((List<Object>) inner.getRaw("sequence")).add("added");
                ((byte[]) inner.getRaw("binary"))[0] = 3;
            });
        });
        assertEquals("raw edit", root.getString("records.0.name"));
        assertEquals(2, ((List<?>) root.getRaw("records.0.sequence")).size());
        assertArrayEquals(new byte[] { 3, 2 }, (byte[]) root.getRaw("records.0.binary"));
    }

    @Test @SuppressWarnings("unchecked")
    void mapViewsSupportReplacementAndRemoval() {
        MemoryDataKey root = document();
        root.writeAtomically(outer -> outer.getRelative("records.0").writeAtomically(inner -> {
            Map<String, Object> raw = (Map<String, Object>) inner.getRaw("");
            raw.put("removed", "value");
            raw.entrySet().stream().filter(entry -> entry.getKey().equals("name")).findFirst().orElseThrow().setValue("entry edit");
            var iterator = raw.keySet().iterator();
            while (iterator.hasNext()) if (iterator.next().equals("removed")) iterator.remove();
            raw.computeIfAbsent("added", unused -> "computed");
        }));
        assertEquals("entry edit", root.getString("records.0.name"));
        assertEquals("computed", root.getString("records.0.added"));
        assertFalse(root.keyExists("records.0.removed"));
    }

    @Test void rootReplacementAndClearPublishOnlyTheFinalDocument() {
        MemoryDataKey root = document();
        root.writeAtomically(outer -> {
            outer.getRelative("records.0").writeAtomically(inner -> {
                inner.getFromRoot("").setRaw("", Map.of("replacement", Map.of("value", "new")));
            });
            assertEquals(Set.of("replacement"), outer.getValuesDeep().keySet());
            outer.writeAtomically(inner -> {
                inner.removeKey(""); inner.setString("final.value", "retained");
            });
        });
        assertEquals(Map.of("final", Map.of("value", "retained")), root.getValuesDeep());
    }

    @Test @SuppressWarnings("unchecked")
    void replacingPreviouslyReadSectionsDiscardsOldAliases() {
        MemoryDataKey root = document();
        root.writeAtomically(outer -> {
            outer.getRelative("records.0").writeAtomically(inner -> {
                Map<String, Object> previous = (Map<String, Object>) inner.getRaw("");
                inner.setRaw("", Map.of("name", "replacement"));
                previous.put("name", "discarded");
            });
            outer.getRelative("records.0").writeAtomically(inner -> inner.setString("added", "later"));
        });
        assertEquals(Map.of("name", "replacement", "added", "later"), root.getRelative("records.0").getValuesDeep());
    }

    @Test void preparationFailureCannotPartiallyCommitIntoTheParent() {
        MemoryDataKey root = document();
        root.writeAtomically(outer -> {
            Map<String, Object> before = outer.getValuesDeep();
            assertThrows(IllegalStateException.class, () -> outer.writeAtomically(inner -> {
                inner.setString("global.provider.value", "partial");
                inner.setRaw("broken", new java.util.AbstractMap<>() {
                    @Override public Set<Entry<Object, Object>> entrySet() { throw new IllegalStateException("copy failed"); }
                });
            }));
            assertEquals(before, outer.getValuesDeep());
            outer.setString("continued", "after failure");
        });
        assertEquals("after failure", root.getString("continued"));
        assertEquals("shared", root.getString("global.provider.value"));
    }

    @Test void nestedWritesDoNotTraverseUnrelatedPayloads() {
        MemoryDataKey root = document();
        int[] traversals = { 0 };
        Map<String, Object> unrelated = new java.util.AbstractMap<>() {
            @Override public Set<Map.Entry<String, Object>> entrySet() {
                traversals[0]++; return Map.<String, Object>of("payload", List.of("unchanged")).entrySet();
            }
        };
        root.writeAtomically(outer -> {
            outer.setRaw("unrelated", unrelated);
            for (int pass = 0; pass < 20; pass++) {
                final int value = pass;
                outer.getRelative("records.0").writeAtomically(inner -> inner.setInt("pass", value));
            }
            assertEquals(0, traversals[0], "only the enclosing publication should copy this payload");
        });
        assertEquals(1, traversals[0]);
        assertEquals(19, root.getInt("records.0.pass"));
        assertEquals(Map.of("payload", List.of("unchanged")), root.getRaw("unrelated"));
    }

    @Test void threeTransactionLevelsAndExplicitCopiesStayIndependent() {
        MemoryDataKey root = document();
        DataKey[] copied = new DataKey[1];
        root.writeAtomically(outer -> outer.getRelative("records.0").writeAtomically(middle -> {
            middle.writeAtomically(inner -> {
                inner.setString("name", "deepest"); copied[0] = inner.copyWithRoot();
            });
            assertEquals("deepest", middle.getString("name"));
        }));
        copied[0].setString("name", "copy edit");
        copied[0].getFromRoot("global.provider").setString("value", "copy global edit");
        assertEquals("deepest", root.getString("records.0.name"));
        assertEquals("shared", root.getString("global.provider.value"));
    }

    private static MemoryDataKey document() {
        MemoryDataKey root = new MemoryDataKey();
        root.setString("records.0.name", "old"); root.setString("global.provider.value", "shared");
        return root;
    }
}
