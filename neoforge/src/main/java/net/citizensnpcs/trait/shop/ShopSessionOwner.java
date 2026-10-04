package net.citizensnpcs.trait.shop;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Runtime ownership shared by a shop definition and each independently replaceable stock object. */
final class ShopSessionOwner {
    private boolean retired;
    private Object suspension;
    private int trades;
    private final Set<Session<?>> sessions = new HashSet<>();

    private record Session<T>(WeakReference<T> target, Consumer<T> close) {
        void end() { T value = target.get(); if (value != null) close.accept(value); }
    }

    <T> Runnable track(T target, Consumer<T> close) {
        if (!isActive()) throw new IllegalStateException("Shop session owner is unavailable");
        sessions.removeIf(session -> session.target().get() == null);
        Session<T> session = new Session<>(new WeakReference<>(target), close);
        sessions.add(session);
        return () -> sessions.remove(session);
    }

    boolean isActive() { return !retired && suspension == null; }
    boolean beginTrade() { if (!isActive()) return false; trades++; return true; }
    void endTrade() { trades--; }
    void requireIdle() {
        if (trades != 0) throw new IllegalStateException("Cannot replace shop ownership during a transaction");
    }

    /** Close editors before saving, retaining a way to resume admission if replacement fails. */
    Runnable suspend() {
        requireIdle();
        if (!isActive()) throw new IllegalStateException("Shop session owner is unavailable");
        Object token = new Object();
        suspension = token;
        try { closeTracked(); }
        catch (RuntimeException | Error failure) { suspension = null; throw failure; }
        return () -> { if (suspension == token) suspension = null; };
    }

    void retire() {
        requireIdle();
        if (retired) return;
        retired = true;
        closeTracked();
    }

    private void closeTracked() {
        var pending = new ArrayList<>(sessions);
        sessions.clear();
        Throwable failure = null;
        for (Session<?> session : pending) {
            try { session.end(); }
            catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error ex) throw ex;
    }
}
