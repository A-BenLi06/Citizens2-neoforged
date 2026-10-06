# Validated delay and speed trigger input

The delay editor previously converted every parse failure into a zero-tick trigger,
and its tick conversion could wrap on overflow. The speed editor accepted NaN,
infinity and finite doubles that overflowed when stored as floats.

Delay input now rejects malformed durations and values exceeding the stored tick
range. Tick counts, duration units and sub-tick truncation remain supported; negative
durations retain their existing zero clamp. Speed input must be finite and representable
as the stored float after the existing finite-negative-to-zero clamp.

Invalid interactive input reports the existing prompt and leaves the current session
value intact. Invalid inline input returns control to the dedicated editor so it can be
repaired. Neither path silently creates an unintended trigger.

Unit tests cover duration/tick boundaries, malformed input, floating-point limits and
valid compatibility cases. Native chat tests verify retained state, repair, completion
and execution of a valid speed trigger. This validates editor input, not arbitrary
manually edited save files or third-party trigger constructors. Interactions remains
an independent NeoForge JAR.
