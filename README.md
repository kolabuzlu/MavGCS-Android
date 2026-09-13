# MavGCS

A simple MAVLink ground control station for Android tablets. Landscape Compose UI with a map, attitude indicator, live telemetry, and basic vehicle commands.

## What it does

- Listens for MAVLink v1/v2 over **UDP** (default `0.0.0.0:14550`) or connects as a **TCP** client
- Sends a GCS heartbeat (system 255, component 190)
- Shows armed state, flight mode, GPS, battery, altitude, speed, and attitude
- Plots position on an OpenStreetMap view
- Sends ARM / DISARM, TAKEOFF, LAND, RTL, LOITER, GUIDED, AUTO, STABILIZE

ArduPilot Copter/Plane and PX4 custom modes are both decoded.

## Open and run

This machine did not have a JDK or Android SDK installed, so the app is meant to be built in Android Studio.

1. Install [Android Studio](https://developer.android.com/studio) (Ladybug or newer).
2. **File → Open** and choose this folder (`MavGCS`).
3. Let Gradle sync; install the SDK/platform 35 prompt if asked.
4. Plug in a tablet (USB debugging) or start a **tablet** emulator, landscape.
5. Run the `app` configuration.

Min SDK 26, target 35. The activity is locked to landscape for tablet use.

## Connect a vehicle

Put the tablet on the same Wi-Fi as the autopilot or SITL PC.

**UDP (typical)**  
In MavGCS: `UDP listen`, bind `0.0.0.0`, port `14550`, then **Connect**.

Point SITL or MAVProxy at the tablet:

```text
sim_vehicle.py -v ArduCopter --out=udp:<TABLET_IP>:14550
```

or

```text
mavproxy.py --master=udp:127.0.0.1:14550 --out=udp:<TABLET_IP>:14550
```

**TCP**  
Use the PC address and the autopilot/MAVProxy TCP port (often `5760`). On an emulator, the host loopback address is `10.0.2.2`.

## Safety

Commands go to the vehicle with no extra confirmation beyond the on-screen buttons. Use a simulator first. Keep a hardware kill switch or transmitter override when you test on hardware.

## Project layout

- `app/src/main/java/com/mavgcs/app/mavlink` — UDP/TCP link and MAVLink parsing (`pymavlink`-style stack via DroneFleet)
- `app/src/main/java/com/mavgcs/app/ui` — tablet HUD, map, and command pad
