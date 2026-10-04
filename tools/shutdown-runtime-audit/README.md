# Shutdown lifecycle audit

From the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-shutdown-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/shutdown-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/shutdown-runtime-audit.gradle runServer --console=plain
```

Confirm port `25640` is available; run processes sequentially. The isolated server binds
to `127.0.0.1:25640` and uses only `artifacts/shutdown-audit-server`. It stops itself.
Never copy production data into this fixture. Audit classes are opt-in; rebuild normally
before distributing release archives.

The fixture creates normal/packet NPCs in default, named, anonymous and temporary
registries, queues a respawn, installs trait listeners and delayed sync/async tasks,
and prepares connection-free actors with editors and chat prompts. It then requests
actual server shutdown, injecting a save error, despawn vetoes, a throwing trait cleanup,
and throwing editor/prompt callbacks. The failing save callback also removes a named
registry; its NPC must still be retired. Verification runs in the later stopping listener
and stopped event, not a substitute shutdown routine.

Expected: `[SHUTDOWNAUDIT] COMPLETE 59 checks`
and `BUILD SUCCESSFUL`. Four intentional error diagnostics identify editor cleanup,
chat prompt cleanup, NPC/shop save and default-registry trait cleanup. They must not
prevent later cleanup or other shutdown event listeners. The fixture also verifies
idempotency, rejected reentry, task cancellation, empty queues/bindings, and 19 session
lifecycle checks including dispatcher re-registration before the actual server stop.

This does not simulate a full physical-client reconnect/integrated-server restart,
an installed LuckPerms backend, arbitrary third-party side effects, or asynchronous
code that ignores interruption. Scheduler and LuckPerms bridge tests supplement the
native fixture through the ordinary JUnit suite.
