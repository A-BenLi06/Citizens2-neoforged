package net.citizensnpcs.api.npc.templates;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.DelegatePersistence;
import net.citizensnpcs.api.persistence.Persister;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;

/**
 * Applies a template's {@code yaml_replace:} block: the NPC is saved to a scratch key, the replacement values are written
 * over it, and the NPC is loaded back from the result.
 * <p>
 * {@code override: false} (the default) only fills in keys the NPC does not already have, so a template can supply
 * defaults without stamping on an NPC's own settings; {@code override: true} wins over them.
 */
public class YamlReplacementAction implements Consumer<NPC> {
    private final boolean override;
    private final Map<String, Object> replacements;

    @DelegatePersistence(YRAPersister.class)
    private YamlReplacementAction(boolean override, Map<String, Object> replacements) {
        this.replacements = replacements;
        this.override = override;
    }

    @Override
    public void accept(NPC npc) {
        MemoryDataKey memoryKey = new MemoryDataKey();
        npc.save(memoryKey);
        // breadth-first over the replacement tree, so a nested map becomes dotted keys the data key understands
        List<Node> queue = new ArrayList<>();
        queue.add(new Node("", replacements));
        for (int i = 0; i < queue.size(); i++) {
            Node node = queue.get(i);
            for (Entry<String, Object> entry : node.map.entrySet()) {
                String fullKey = node.headKey.isEmpty() ? entry.getKey() : node.headKey + '.' + entry.getKey();
                if (entry.getValue() instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> child = (Map<String, Object>) entry.getValue();
                    queue.add(new Node(fullKey, child));
                    continue;
                }
                if (memoryKey.keyExists(fullKey) && !override) {
                    continue;
                }
                memoryKey.setRaw(fullKey, entry.getValue());
            }
        }
        npc.load(memoryKey);
    }

    private static class Node {
        final String headKey;
        final Map<String, Object> map;

        private Node(String headKey, Map<String, Object> map) {
            this.headKey = headKey;
            this.map = map;
        }
    }

    private static class YRAPersister implements Persister<YamlReplacementAction> {
        public YRAPersister() {
        }

        @Override
        public YamlReplacementAction create(DataKey root) {
            return new YamlReplacementAction(root.getBoolean("override"),
                    root.getRelative("replacements").getValuesDeep());
        }

        @Override
        public void save(YamlReplacementAction instance, DataKey root) {
            root.setBoolean("override", instance.override);
            root.setRaw("replacements", instance.replacements);
        }
    }
}
