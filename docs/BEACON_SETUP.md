# Bluetooth beacon setup

For milestone M4b and the mall trial. Beacons are small battery boxes that broadcast a short ID a few
times a second. The app listens for our IDs and logs their signal strength. Later, the positioning
uses that strength to tell roughly where along the corridor the phone is. Beacons collect nothing:
they only broadcast, and the phone only listens.

---

## 1. What to buy

**How many:** 6–10 for the trial route (one per ~10 m of route, plus a couple of spares).

**Must have:**

- [ ] **iBeacon** broadcasting (almost all retail beacons do this; Eddystone-UID also works).
- [ ] **Configurable from a phone app:** UUID, major, minor, transmit power, advertising interval.
- [ ] **Non-connectable / "deploy" mode:** the beacon only broadcasts and refuses connections, except
      for a short window after a button press or with the password.
- [ ] **Password-protected settings:** so nobody can reconfigure or drain it.
- [ ] **Device name can be blanked** (or set to something meaningless), so it doesn't appear as a
      named device if a shopper opens their Bluetooth list.
- [ ] **Battery life ≥ 1 year at 200 ms interval** (usually a CR2477 coin cell or 2× AA). USB-powered
      ones are fine where there's a socket.

**Nice to have:** screw holes or a lockable mount, a plain grey/white case, and battery level reported
in the vendor app.

Common makers of retail beacons include Minew, Feasycom, KKM, Blue Charm and Kontakt.io; they are sold on
Lazada, Shopee and AliExpress. Before buying in bulk, check the listing against the list above, then
buy one first and do the desk check in section 5.

## 2. Configure each beacon (in the vendor's app)

| Setting | Value |
| --- | --- |
| UUID | `D55248B4-8F80-46C8-88AC-EEC9C72B2B7C` (the same for all our beacons) |
| Major | Level number: the level's position in the map's level list, counting from 1 (the first level is 1). The map editor fills this in for you. |
| Minor | Beacon number: B01 → 1, B02 → 2, … (unique on each level; simplest to keep it unique everywhere) |
| Advertising interval | 200 ms (5 broadcasts a second) |
| Transmit power | A middle-to-high setting, around −4 to 0 dBm. Write down what you chose. |
| Measured power (RSSI at 1 m) | Leave the factory value |
| Other broadcasts | Turn off everything except iBeacon (Eddystone URL/TLM, the vendor's own frames) |
| Connectable | Off (deploy / non-connectable mode) |
| Device name | Blank, or a meaningless code |
| Password | Change it from the default. Keep it in your password manager, never in the repo. |

Then **label each beacon** on the inside or back with its id (B01), level and the date you put the
battery in.

## 3. Mount them

- [ ] Along the test route, **about every 10 m**, alternating sides of the corridor if you can.
- [ ] **2.5–3 m high**, above head height so bodies block less signal, and out of easy reach.
- [ ] With a **clear view of the corridor**: not inside metal boxes, not behind signage, shutters or
      a big pillar, not on top of a Wi-Fi access point.
- [ ] **Firmly attached:** removable mounting tape for the trial (with the mall's OK), screws or a
      lockable mount for anything longer.
- [ ] Note any beacon you had to place somewhere awkward (e.g. on a metal column); it helps explain
      odd readings later.

## 4. Record them in the map

1. Measure each beacon's position like the QR plates: distances from two known corners with the laser
   meter, and its height above the floor.
2. In the map editor, choose the **Bcn** tool (key **B**) and click the spot. Set the exact position and
   height in the **Selection** panel.
3. Check **UUID, Major and Minor** in the Selection panel match what you set in the beacon. These must
   match, or the app won't know which beacon it's hearing.
4. Export `map.json`. The checks panel flags a wrong UUID format, numbers outside 0–65535 and two
   beacons with the same identity.

## 5. Desk check (M4b)

With one configured beacon, before buying the rest:

1. Put the beacon on the desk, 1–3 m from the phone.
2. In Geger Trial, check the setup screen says **Radios: Bluetooth, Wi-Fi and Location on**. If not, tap
   **Grant** or switch the radio on.
3. Start a recording. The **BT:** line should show **1 beacon(s)** with a signal around **−50 to
   −90 dBm**. Without a map, it shows the beacon's full identity instead of B01.
4. Record for 2–3 minutes, walking a few metres away and back once. The number should drop (more
   negative) as you walk away.
5. Stop and export. On the laptop:
   `./gradlew -q :replay-cli:run --args="check-session <session.zip>"`
   should list the beacon with **several broadcasts a second** and a median signal in the −50 to
   −90 dBm range. No other Bluetooth devices appear, by design.

Send the zip if anything looks off.

## 6. Questions for the mall's IT team

Wi-Fi can help positioning if the mall's access points support ranging. Ask:

1. Which **Wi-Fi access point brand and models** are installed?
2. Do they support **802.11mc / FTM (Wi-Fi RTT, "fine timing measurement")**, and can it be switched on?
3. Can they share the **access point positions** on the floor plan?
4. Is it OK to mount **battery beacons** along the test route (where, and how they'd like them fixed)?

The app shows on the setup screen whether the phone supports Wi-Fi ranging, and records which
access points answer, so a short walk will also tell us.

## Upkeep and risks

- **Batteries:** each session log lists every beacon heard and its signal. A beacon that disappears,
  or gets steadily weaker, needs a battery.
- **Spoofing:** anyone can copy a beacon's broadcast. The app will treat beacons as hints, checked
  against the map, steps and other beacons, never as the only source of position.
- **Tampering:** beacons mounted high, password-locked and in plain cases are rarely touched. Note
  any that move; a moved beacon must be re-surveyed in the map.
