# DashCast Satellite

This is an independent Android companion. Keep OEM outputs, CAN, ADB, vehicle diagnostics and
platform signing in DashCast. Never add vehicle keys here. One app; no shared-code repository yet.

The protocol and fixtures under protocol/v1 are pinned to a reviewed receiver commit in
upstream.json. Verify hashes with tools/verify_protocol.py and run fixture tests when editing
transport or navigation. Preserve real source age; a timer alone cannot refresh cached guidance.
Never log tokens, SDP, notifications or route text. Unknown maneuvers must remain unsupported.

Before answering codebase questions, run graphify query when graphify-out/graph.json exists.
Use graphify path/explain for focused relationships and wiki/index.md for broad navigation when
available. Dirty graph outputs do not justify skipping graphify. After code changes run
graphify update . (AST-only). For /graphify, read the installed graphify skill first.

Validation: python3 tools/verify_protocol.py and
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug.
Actual vehicle acceptance is tracked in docs/CARLINKIT_VALIDATION.md; never infer it from JVM tests.
