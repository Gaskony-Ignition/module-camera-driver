
## Disabling a camera device does not stop go2rtc pulling from it

**Found 08/08/2026** while capturing README screenshots.

`FrontPTZ` was disabled through Connections → Devices (confirmed
`ENABLED=false`, and the module's own extension-point registry correctly
dropped it from the device counts). go2rtc nonetheless kept its stream
registered and its producer active — `GET /api/streams` still listed
`FrontPTZ`, and Diagnostics reported "Registered 3, Active Producers 3". The
camera was still being connected to and pulled from after the operator had
disabled it in Ignition.

**Why.** `CameraDevice.onShutdown()` does call `go2RtcManager.removeStream()`,
but it is gated on an instance flag:

```java
if (go2RtcStreamRegistered && go2RtcManager != null) {   // CameraDevice.java ~951
    go2RtcManager.removeStream(context.getName());
    go2RtcStreamRegistered = false;
}
```

`go2RtcStreamRegistered` is per-`CameraDevice`-instance state, set only when
*this* instance's `addStream()` succeeded (~line 311). Any path that leaves the
go2rtc stream alive while the flag is false — a device object recreated after a
module reload, an `addStream` that registered server-side but returned false,
a prior instance shut down uncleanly — orphans the stream permanently. Nothing
reconciles go2rtc's registry against the configured devices afterwards.

**Why it matters beyond tidiness.** go2rtc holds the camera's credentials in
the producer URL, so an orphaned stream is a live authenticated connection to a
camera the operator believes is disabled. It also means "disabled" does not
stop recording/streaming load, and the module's own status page will disagree
with go2rtc about what is running.

**Fix**: do not gate removal on the flag — always ask go2rtc to remove the
stream by name on shutdown and treat "not found" as success. Better, add a
reconcile pass on module startup that deletes any go2rtc stream with no
corresponding enabled device.

**Workaround**: `DELETE /api/streams?name=<device>` against go2rtc directly.
