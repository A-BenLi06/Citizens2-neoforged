package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class ConditionsTest {
    private static boolean literal(String expression) { return Conditions.holds(expression, Function.identity()); }
    private static boolean expanded(String expression, Map<String, String> values) {
        return Conditions.holds(expression, operand -> Conditions.expandOperand(operand, values::get));
    }

    @Test void allOriginalOperatorsAndTheirNegations() {
        for (String expression : List.of("A == A", "A != a", "A equals A", "A !equals a",
                "A equalsIgnoreCase a", "A !equalsIgnoreCase b", "Alphabet startsWith ALP",
                "Alphabet !startsWith bet", "Alphabet contains HAB", "Alphabet !contains xyz",
                "2 > 1", "2 >= 2", "1 < 2", "2 <= 2")) assertTrue(literal(expression), expression);
        for (String expression : List.of("A == a", "A != A", "A equals a", "A !equals A",
                "A equalsIgnoreCase b", "A !equalsIgnoreCase a", "Alphabet startsWith bet",
                "Alphabet !startsWith ALP", "Alphabet contains xyz", "Alphabet !contains HAB",
                "2 > 2", "1 >= 2", "2 < 2", "2 <= 1")) assertFalse(literal(expression), expression);
    }

    @Test void literalDelimitersAndSignificantOperandWhitespace() {
        for (String expression : List.of("a==a", "a\t==\ta", "a EQUALS a", "a == A OR a == a",
                " a == a", "a == a ", "a  == a", "a ==  a", "", "a")) assertFalse(literal(expression), expression);
        assertTrue(literal(" a ==  a"));
        assertTrue(literal(" == "));
        assertTrue(literal("x startsWith "));
        assertFalse(Conditions.holds(null, Function.identity()));
        assertTrue(Conditions.all(List.of(), null));
    }

    @Test void orAlternativesShortCircuitAndListEntriesRemainAnded() {
        assertTrue(literal("x == y or a == a or b == c"));
        assertTrue(literal("x == y or b == c or a == a"));
        assertFalse(literal("x == y or b == c"));
        assertTrue(literal("malformed or a == a"));
        var visited = new ArrayList<String>();
        assertTrue(Conditions.holds("left == right or forbidden == later", value -> {
            visited.add(value); return "same";
        }));
        assertEquals(List.of("left", "right"), visited);
        assertFalse(Conditions.all(List.of("a == a or b == c", "x == y"), null));
        assertTrue(Conditions.all(List.of("a == a", "x == y or b == b"), null));
    }

    @Test void completeOperandsKeepAndAsTextAndExpandBothSides() {
        var values = Map.of("%a%", "yes", "%b%", "no", "%c%", "yes", "%d%", "no");
        assertTrue(expanded("%a% and %b% == yes and no", values));
        assertTrue(expanded("before %a% and %b% after == before %c% and %d% after", values));
        assertFalse(expanded("%a% and %b% == yes", values));
        assertTrue(expanded("%a%%b%%a% == yesnoyes", values));
        assertTrue(literal("rock and roll == rock and roll"));
    }

    @Test void unknownValuesNeverSatisfyNegativeComparisons() {
        for (String operator : List.of("==", "!=", "equals", "!equals", "equalsIgnoreCase",
                "!equalsIgnoreCase", "startsWith", "!startsWith", "contains", "!contains", ">", ">=", "<", "<=")) {
            assertFalse(expanded("prefix %unknown% " + operator + " other", Map.of()), operator);
            assertFalse(expanded("other " + operator + " prefix %unknown%", Map.of()), operator);
        }
        assertTrue(expanded("%unknown% != 0 or %known% == yes", Map.of("%known%", "yes")));
        assertFalse(expanded("prefix %known% %unknown% != other", Map.of("%known%", "yes")));
        assertFalse(expanded("%unknown_%known%% == yes", Map.of("%known%", "yes")));
    }

    @Test void delimitersInsidePlaceholderArgumentsAreNotGrammar() {
        String token = "%checkitem_lorecontains:Rank > 3 or Class == A,amt:1%";
        assertTrue(expanded(token + " == yes", Map.of(token, "yes")));
        assertTrue(expanded(token + " == no or ok == ok", Map.of(token, "yes")));
        assertTrue(expanded("%value% == a == b", Map.of("%value%", "a == b")));
        assertTrue(expanded("%value% == a or false == true", Map.of("%value%", "a")));
        assertFalse(expanded("%value% == a", Map.of("%value%", "a or a == a")));
    }

    @Test void repeatedOperatorsPreserveOriginalInterpretationOrder() {
        assertFalse(literal("a == a == a"));
        // After false equality, the original tries contains with the earlier text as its left operand.
        assertTrue(literal("a == a contains a"));
    }

    @Test void numericComparisonsKeepExactFiniteValues() {
        assertTrue(literal("0.1 < 0.10000000000000001"));
        assertTrue(literal("9007199254740993 > 9007199254740992"));
        assertTrue(literal("-1e100 < -1e99"));
        assertTrue(literal(" 1 >  0 "));
        for (String expression : List.of("NaN >= 0", "Infinity > 0", "bad >= 0", "0 <= bad"))
            assertFalse(literal(expression), expression);
    }

    @Test void stringMatchingDoesNotChangeWithTheJvmLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(literal("INVENTORY contains inventory"));
            assertTrue(literal("ITEM startsWith item"));
            assertFalse(literal("ITEM == item"));
        } finally { Locale.setDefault(previous); }
    }

    @Test void originalTokensExpandOnceWithOneBraceArgumentPass() {
        var values = Map.of("%level%", "3", "%checkitem_lorecontains:Rank 3%", "yes", "%value%", "%level%");
        assertEquals("yes", Conditions.expandOperand("%checkitem_lorecontains:Rank {level}%", values::get));
        assertEquals("%level%", Conditions.expandOperand("%value%", values::get));
        assertNull(Conditions.expandOperand("%checkitem_lorecontains:{unknown}%", values::get));
        assertEquals("50% / % spaced %", Conditions.expandOperand("50% / % spaced %", values::get));
        assertEquals("{level}", Conditions.expandOperand("{level}", values::get));
    }
}
