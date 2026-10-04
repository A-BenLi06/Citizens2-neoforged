package net.citizensnpcs.api.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.minecraft.server.Bootstrap;

class PersistenceDelegateFailureTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void aDeclaredDelegateFailureIsReportedAndCanBeRetried() {
        DataKey destination = new MemoryDataKey();
        destination.setString("value", "previous");
        Declared instance = new Declared();
        RetryingDelegate.fail = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PersistenceLoader.save(instance, destination));
        assertTrue(failure.getMessage().contains(RetryingDelegate.class.getName()));
        assertEquals("previous", destination.getString("value"));
        RetryingDelegate.fail = false;
        PersistenceLoader.save(instance, destination);
        assertEquals("written", destination.getString("value"));
    }

    @Test void failedRegistrationKeepsThePreviouslyRegisteredDelegate() {
        PersistenceLoader.registerPersistDelegate(RegisteredValue.class, ValidDelegate.class);
        assertThrows(IllegalStateException.class, () -> PersistenceLoader.registerPersistDelegate(RegisteredValue.class, BrokenDelegate.class));
        DataKey destination = new MemoryDataKey();
        PersistenceLoader.save(new Registered(), destination);
        assertEquals("valid", destination.getString("value"));
    }

    @Test void anUnconstructableDeclaredDelegateCannotBeDroppedFromTheFieldList() {
        DataKey destination = new MemoryDataKey();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> PersistenceLoader.save(new Unconstructable(), destination));
        assertTrue(failure.getMessage().contains(NoDefaultConstructor.class.getName()));
        assertTrue(destination.getValuesDeep().isEmpty());
    }

    static final class Declared {
        @Persist @DelegatePersistence(RetryingDelegate.class) String value = "written";
    }
    public static final class RetryingDelegate implements Persister<String> {
        static boolean fail;
        public RetryingDelegate() { if (fail) throw new IllegalStateException("synthetic delegate initialization failure"); }
        public String create(DataKey key) { return key.getString(""); }
        public void save(String value, DataKey key) { key.setString("", value); }
    }
    static final class RegisteredValue { }
    static final class Registered { @Persist RegisteredValue value = new RegisteredValue(); }
    public static final class ValidDelegate implements Persister<RegisteredValue> {
        public ValidDelegate() { }
        public RegisteredValue create(DataKey key) { return new RegisteredValue(); }
        public void save(RegisteredValue value, DataKey key) { key.setString("", "valid"); }
    }
    public static final class BrokenDelegate implements Persister<RegisteredValue> {
        public BrokenDelegate() { throw new IllegalStateException("synthetic registration failure"); }
        public RegisteredValue create(DataKey key) { return new RegisteredValue(); }
        public void save(RegisteredValue value, DataKey key) { throw new AssertionError("not constructed"); }
    }
    static final class Unconstructable {
        @Persist @DelegatePersistence(NoDefaultConstructor.class) String value = "written";
    }
    public static final class NoDefaultConstructor implements Persister<String> {
        public NoDefaultConstructor(String unused) { }
        public String create(DataKey key) { return key.getString(""); }
        public void save(String value, DataKey key) { key.setString("", value); }
    }
}
