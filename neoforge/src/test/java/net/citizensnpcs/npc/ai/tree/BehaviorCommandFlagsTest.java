package net.citizensnpcs.npc.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BehaviorCommandFlagsTest {
    @Test void onlyCompleteUnquotedFlagsSelectExecutionOptions() {
        assertEquals(new BehaviorTreeParser.ExecutionFlags(true, true),
                BehaviorTreeParser.executionFlags("npc command -o\t-p"));
        assertEquals(new BehaviorTreeParser.ExecutionFlags(false, false),
                BehaviorTreeParser.executionFlags("say -operator -player x-o x-p -op"));
    }

    @Test void quotedAndEscapedTextCannotEnableExecutionOptions() {
        for (String command : new String[]{"say \"-o -p\"", "say '-o -p'", "say `-o -p`",
                "say \\-o \\-p", "tellraw @a {\"text\":\"literal -o -p\"}"})
            assertEquals(new BehaviorTreeParser.ExecutionFlags(false, false),
                    BehaviorTreeParser.executionFlags(command), command);
    }

    @Test void realOptionsAfterQuotedTextStillWork() {
        assertEquals(new BehaviorTreeParser.ExecutionFlags(false, true),
                BehaviorTreeParser.executionFlags("say \"quoted \\\" -o text\" -p"));
        assertEquals(new BehaviorTreeParser.ExecutionFlags(true, false),
                BehaviorTreeParser.executionFlags("say '-p' -o"));
    }
}
