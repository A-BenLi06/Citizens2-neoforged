package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderPlaceholderExportsTest {
    @TempDir Path directory;

    @Test void missingConfigurationCreatesNoFileOrExports() throws Exception {
        Path path=directory.resolve("exports.json");
        assertTrue(ProviderPlaceholderExports.load(path).isEmpty()); assertFalse(Files.exists(path));
    }
    @Test void optionalFieldsAndNativeIdsProduceImmutableDefinitions() throws Exception {
        Path path=directory.resolve("exports.json");
        Files.writeString(path,"""
                {"schema":1,"exports":[{"key":"balance","kind":"ECONOMY_BALANCE","currencyId":"configured"},
                {"key":"task","kind":"FTB_TASK_PROGRESS","taskId":"ab2","permission":"quest.read"}]}
                """);
        var definitions=ProviderPlaceholderExports.load(path);
        assertEquals("citizens_balance",definitions.getFirst().externalKey());
        assertEquals("0000000000000AB2",definitions.get(1).taskId());
        assertEquals("quest.read",definitions.get(1).permission());
        assertThrows(UnsupportedOperationException.class,()->definitions.clear());
    }
    @Test void duplicateReservedAndUnknownKeysPreserveTheConfiguration() throws Exception {
        for(String body:new String[]{
                "{\"schema\":1,\"exports\":[{\"key\":\"balance\",\"kind\":\"ECONOMY_BALANCE\",\"currencyId\":\"a\"},{\"key\":\"balance\",\"kind\":\"ECONOMY_BALANCE\",\"currencyId\":\"b\"}]}",
                "{\"schema\":1,\"exports\":[{\"key\":\"selected_npc_id\",\"kind\":\"FTB_TASK_PROGRESS\",\"taskId\":\"1\"}]}",
                "{\"schema\":1,\"exports\":[{\"key\":\"balance\",\"kind\":\"ECONOMY_BALANCE\",\"currencyId\":\"a\",\"misspelled\":true}]}"}) {
            Path path=directory.resolve("exports.json"); Files.writeString(path,body);
            assertThrows(IOException.class,()->ProviderPlaceholderExports.load(path)); assertEquals(body,Files.readString(path));
        }
    }
    @Test void ownerSpecificFieldsCannotBeMixedOrOmitted() {
        assertThrows(IllegalArgumentException.class,()->new ProviderPlaceholderExports.Export("balance",ProviderPlaceholderExports.Kind.ECONOMY_BALANCE,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new ProviderPlaceholderExports.Export("balance",ProviderPlaceholderExports.Kind.ECONOMY_BALANCE,"a","1",null));
        assertThrows(IllegalArgumentException.class,()->new ProviderPlaceholderExports.Export("task",ProviderPlaceholderExports.Kind.FTB_TASK_PROGRESS,"a","1",null));
        assertThrows(IllegalArgumentException.class,()->new ProviderPlaceholderExports.Export("task",ProviderPlaceholderExports.Kind.FTB_TASK_PROGRESS,null,"0",null));
        assertThrows(IllegalArgumentException.class,()->new ProviderPlaceholderExports.Export("task",ProviderPlaceholderExports.Kind.FTB_TASK_PROGRESS,null,"1"," "));
    }
    @Test void unknownSchemaAndKindsAreExplicitErrors() throws Exception {
        for(String body:new String[]{"{\"schema\":2,\"exports\":[]}","{\"schema\":1,\"exports\":[{\"key\":\"task\",\"kind\":\"GUESS_TASK\",\"taskId\":\"1\"}]}"}) {
            Path path=directory.resolve("exports.json"); Files.writeString(path,body);
            assertThrows(IOException.class,()->ProviderPlaceholderExports.load(path)); assertEquals(body,Files.readString(path));
        }
    }
}
