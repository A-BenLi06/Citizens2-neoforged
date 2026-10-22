package net.citizensnpcs.util;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Adapts completed vanilla fire mutations to the pinned logger's public observation handlers. */
public final class NeoProtectFireObservations {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(NeoProtectFireObservations.class);
    private static boolean incompatible, warned;
    private NeoProtectFireObservations() { }

    public static BlockSnapshot before(Level level,BlockPos pos,BlockState intended) {
        if (!(level instanceof ServerLevel) || incompatible || !ModList.get().isLoaded("neoprotect")
                || level.getBlockState(pos).getBlock()==intended.getBlock()) return null;
        try { return BlockSnapshot.create(level.dimension(),level,pos); }
        catch (RuntimeException failure) { failed(failure); return null; }
    }

    public static void after(BlockSnapshot before) {
        if (before==null) return;
        try {
            if (before.getState().getBlock()==before.getCurrentState().getBlock()) return;
            Object service=Handlers.service.invoke(null);
            if(service==null)return;
            // This is an observation argument for the logger only. Posting a cancellable
            // placement event after mutation would give other listeners false ownership.
            var observation=new BlockEvent.EntityPlaceEvent(before,before.getLevel().getBlockState(before.getPos().below()),null);
            Handlers.spread.invoke(service,observation);
            Handlers.destroy.invoke(service,observation);
            warned=false;
        } catch (java.lang.reflect.InvocationTargetException failure) { failed(failure.getCause()); }
        catch (ReflectiveOperationException | LinkageError failure) { incompatible=true; failed(failure); }
        catch (RuntimeException failure) { failed(failure); }
    }

    private static void failed(Throwable failure) {
        if(!warned)LOGGER.error("NeoProtect could not retain a native fire observation; inspect provider storage and compatibility",failure);
        warned=true;
    }

    private static final class Handlers {
        static final Method service,spread,destroy;
        static {
            try {
                service=Class.forName("dev.northbladetech.neoprotect.NeoProtect").getMethod("service");
                var type=Class.forName("dev.northbladetech.neoprotect.NeoProtectService");
                spread=type.getMethod("onFireSpread",BlockEvent.EntityPlaceEvent.class);
                destroy=type.getMethod("onFireDestroy",BlockEvent.EntityPlaceEvent.class);
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }
}
