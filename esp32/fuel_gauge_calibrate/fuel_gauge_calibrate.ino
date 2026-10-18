/*
 * fuel_gauge_calibrate.ino
 * ------------------------------------------------------------------
 * Standalone calibration rig for the Freelander EV fuel gauge.
 * No CAN, no Bluetooth, no SDO polling — the only job is to drive the
 * VTL5C4 vactrol at a chosen level so you can read the needle.
 *
 * WHY MANUAL STEPPING
 *   The gauge is heavily damped, so a timed sweep never settles. You
 *   cycle the car ignition after each level instead: the needle drops
 *   to rest and drives straight to the new position.
 *
 *   An ignition cycle may also power-cycle the ESP32, so the current
 *   step and every reading are written to flash (NVS). A reboot resumes
 *   where you were and re-applies the drive level immediately.
 *
 * 12-BIT PWM
 *   Measurement showed the gauge's whole upper half sits below 8 % drive.
 *   At the Arduino default of 8 bits that is duty 0..20 of 255 — roughly
 *   4 counts per eighth of a tank, far too coarse to place a needle. This
 *   sketch and freelander-esp32.ino both use 12-bit (0..4095), giving ~41
 *   counts per 1 % drive. Drive % is the interchange unit between them,
 *   so calibration transfers unchanged.
 *
 * WIRING  (identical to the car install)
 *   GPIO25 --[100R]--> vactrol LED anode
 *   vactrol LED cathode --> GND
 *   vactrol LDR legs --> in place of the fuel sender, at the sender plug
 *
 * THE ONE RULE THAT MATTERS
 *   M records what the needle is showing RIGHT NOW, then moves to the
 *   next level. So the order is always:
 *       read needle -> M <what you saw> -> ignition OFF/ON -> wait -> read
 *   The number after M is the NEEDLE POSITION in eighths, never the
 *   drive level. 8 = FULL, 4 = 1/2, 0 = EMPTY, 5.5 = between marks.
 *
 * COMMANDS
 *   M <n>       record needle for the current level, eighths 0..8
 *   E <d> <n>   record needle n against drive level d, without moving
 *               (use this to fix a mistyped entry — no ignition cycle)
 *   J <d>       jump the sequence to drive level d and apply it
 *   N / B       next / previous level
 *   R           re-apply current level
 *   S <d>       drive an arbitrary level, d = 0..100 %, sequence unchanged
 *   T           table collected so far
 *   C           generate the freelander-esp32.ino calibration arrays
 *   Z           erase all recordings
 *   ?           help
 * ------------------------------------------------------------------
 */

#include <Arduino.h>
#include <Preferences.h>
#include <math.h>

// ---- hardware ----------------------------------------------------
#define FUEL_PWM_PIN   GPIO_NUM_25    // must match freelander-esp32.ino
#define FUEL_PWM_BITS  12
#define FUEL_PWM_MAX   4095.0f

// ---- levels to walk ----------------------------------------------
// Fractional levels below 8 % because 8 % measured only 3/4 of a tank,
// so FULL and 7/8 live in the very bottom of the drive range. The upper
// end runs to 100 % to find 1/8 and EMPTY.
static const float LEVELS[] = {
  0, 0.5f, 1, 1.5f, 2, 3, 4, 5, 6, 8, 11, 14, 17, 20,
  24, 29, 35, 42, 50, 60, 70, 80, 90, 100
};
static const int NLEV = sizeof(LEVELS) / sizeof(LEVELS[0]);

// ---- persisted state ---------------------------------------------
Preferences prefs;
static float obs[NLEV];        // needle reading in eighths; NAN = not recorded
static int   cur = 0;          // index into LEVELS
static float freeDrive = -1;   // set by "S d"; <0 = following LEVELS[cur]

// ------------------------------------------------------------------
// state persistence
// ------------------------------------------------------------------
void saveState() {
  prefs.putBytes("obs2", obs, sizeof(obs));
  prefs.putInt("cur2", cur);
  // freeDrive is saved too: an "S 14" must survive the ignition cycle, or the
  // needle springs back to the sequence level the moment power returns and
  // there is nothing to read.
  prefs.putFloat("free2", freeDrive);
}

void loadState() {
  bool ok = (prefs.getBytesLength("obs2") == sizeof(obs));
  if (ok) prefs.getBytes("obs2", obs, sizeof(obs));
  if (!ok) for (int i = 0; i < NLEV; i++) obs[i] = NAN;
  cur = prefs.getInt("cur2", 0);
  if (cur < 0 || cur >= NLEV) cur = 0;
  freeDrive = prefs.getFloat("free2", -1.0f);
}

int recordedCount() {
  int n = 0;
  for (int i = 0; i < NLEV; i++) if (!isnan(obs[i])) n++;
  return n;
}

// Nearest level index to a drive value, for E and J.
int nearestLevel(float d) {
  int best = 0;
  float bd = fabsf(LEVELS[0] - d);
  for (int i = 1; i < NLEV; i++) {
    float x = fabsf(LEVELS[i] - d);
    if (x < bd) { bd = x; best = i; }
  }
  return best;
}

// ------------------------------------------------------------------
// PWM output
// ------------------------------------------------------------------
void applyDrive(float pct, bool announce = true) {
  pct = constrain(pct, 0.0f, 100.0f);
  int duty = (int)roundf(pct * FUEL_PWM_MAX / 100.0f);
  analogWrite(FUEL_PWM_PIN, duty);
  if (announce)
    Serial.printf("    [pin] GPIO%d  drive %.4g %%  ->  pwm %d/%d\n",
                  (int)FUEL_PWM_PIN, pct, duty, (int)FUEL_PWM_MAX);
}

float activeDrive() { return (freeDrive >= 0) ? freeDrive : LEVELS[cur]; }

// ------------------------------------------------------------------
// pretty-printing needle positions
// ------------------------------------------------------------------
const char* eighthsLabel(float e) {
  if (isnan(e)) return "-";
  int i = (int)roundf(e * 2.0f);            // half-eighth resolution
  if (fabsf(e * 2.0f - i) > 0.01f) return "";
  switch (i) {
    case 0:  return "EMPTY";
    case 2:  return "1/8";
    case 4:  return "1/4";
    case 6:  return "3/8";
    case 8:  return "1/2";
    case 10: return "5/8";
    case 12: return "3/4";
    case 14: return "7/8";
    case 16: return "FULL";
    default: return "";
  }
}

void printStep() {
  Serial.println();
  Serial.printf("=== step %d of %d — drive %.4g %% ==============\n",
                cur + 1, NLEV, LEVELS[cur]);
  if (!isnan(obs[cur]))
    Serial.printf("  (already recorded as %.2f/8 %s — M overwrites)\n",
                  obs[cur], eighthsLabel(obs[cur]));
  Serial.println("  NOW: ignition OFF, ignition ON, let the needle settle.");
  Serial.println("  THEN: M <eighths of the needle>   8=FULL 4=1/2 0=EMPTY");
}

// ------------------------------------------------------------------
// results table
// ------------------------------------------------------------------
void printTable() {
  Serial.println();
  Serial.println("  drive %   needle/8   reads as");
  Serial.println("  -------   --------   --------");
  for (int i = 0; i < NLEV; i++) {
    const char* mark = (i == cur) ? "  <-- current" : "";
    if (isnan(obs[i]))
      Serial.printf("   %6.4g       --         --     %s\n", LEVELS[i], mark);
    else
      Serial.printf("   %6.4g     %5.2f      %-6s %s\n",
                    LEVELS[i], obs[i], eighthsLabel(obs[i]), mark);
  }
  Serial.printf("  %d of %d levels recorded.\n", recordedCount(), NLEV);
}

// ------------------------------------------------------------------
// generate the freelander-esp32.ino arrays
// ------------------------------------------------------------------
// freelander-esp32.ino interpolates drive % over ASCENDING SOC. We want the
// needle to read proportionally to SOC, so a needle at e/8 is exactly
// what SOC = e/8 * 100 should look like. That inverts each recorded
// (drive, needle) pair into the (soc, drive) pair the firmware needs.
void printArrays() {
  const int n = recordedCount();
  if (n < 3) {
    Serial.println("\n  Need at least 3 recorded levels. Keep going.");
    return;
  }

  float soc[NLEV + 2], drv[NLEV + 2];
  int m = 0;
  for (int i = 0; i < NLEV; i++) {
    if (isnan(obs[i])) continue;
    soc[m] = constrain(obs[i] / 8.0f * 100.0f, 0.0f, 100.0f);
    drv[m] = LEVELS[i];
    m++;
  }

  // Sort ascending by SOC (insertion sort; m is tiny).
  for (int i = 1; i < m; i++) {
    float s = soc[i], d = drv[i];
    int j = i - 1;
    while (j >= 0 && soc[j] > s) { soc[j+1] = soc[j]; drv[j+1] = drv[j]; j--; }
    soc[j+1] = s; drv[j+1] = d;
  }

  // Average duplicate SOC values (two levels landing on the same mark).
  int k = 0;
  for (int i = 0; i < m; ) {
    int j = i; float sum = 0;
    while (j < m && fabsf(soc[j] - soc[i]) < 0.01f) { sum += drv[j]; j++; }
    soc[k] = soc[i]; drv[k] = sum / (j - i); k++;
    i = j;
  }
  m = k;

  bool noEmpty = (soc[0]   > 0.01f);
  bool noFull  = (soc[m-1] < 99.99f);

  // Clamp the ends so the table spans 0..100 % SOC.
  if (noEmpty) {
    for (int i = m; i > 0; i--) { soc[i] = soc[i-1]; drv[i] = drv[i-1]; }
    soc[0] = 0.0f; m++;
  }
  if (noFull) { soc[m] = 100.0f; drv[m] = drv[m-1]; m++; }

  // Sanity check: drive must fall as SOC rises.
  bool mono = true;
  for (int i = 1; i < m; i++) if (drv[i] > drv[i-1] + 0.01f) mono = false;

  Serial.println();
  Serial.println("  ---- paste into freelander-esp32.ino, replacing the ----");
  Serial.println("  ---- FUEL_SOC_PTS / FUEL_DRIVE_PTS / FUEL_NPTS ----");
  Serial.println();
  Serial.print("static const float FUEL_SOC_PTS[]   = { ");
  for (int i = 0; i < m; i++) Serial.printf("%s%.4g", i ? ", " : "", soc[i]);
  Serial.println(" };");
  Serial.print("static const float FUEL_DRIVE_PTS[] = { ");
  for (int i = 0; i < m; i++) Serial.printf("%s%.4g", i ? ", " : "", drv[i]);
  Serial.println(" };");
  Serial.printf("static const int   FUEL_NPTS = %d;\n", m);
  Serial.println();

  if (noEmpty)
    Serial.println("  note: EMPTY never reached — SOC 0 % reuses your highest drive.");
  if (noFull)
    Serial.println("  note: FULL never reached — SOC 100 % reuses your lowest drive.");
  if (!mono) {
    Serial.println("  WARNING: drive % does not fall consistently as SOC rises.");
    Serial.println("  A reading is probably transposed. Check T, then fix it with");
    Serial.println("  E <drive> <eighths> — no ignition cycle needed.");
  }
  Serial.printf("  Built from %d readings.\n", n);
}

// ------------------------------------------------------------------
void printHelp() {
  Serial.println();
  Serial.println("  M <n>      record needle NOW, eighths 0..8, then advance");
  Serial.println("  E <d> <n>  set needle n for drive level d, without moving");
  Serial.println("  J <d>      jump to drive level d");
  Serial.println("  N / B      next / previous level");
  Serial.println("  R          re-apply level     S <d>  drive any level");
  Serial.println("  T          table              C      generate arrays");
  Serial.println("  Z          erase all          ?      help");
}

// ------------------------------------------------------------------
void gotoLevel(int i) {
  cur = constrain(i, 0, NLEV - 1);
  freeDrive = -1;
  saveState();
  applyDrive(LEVELS[cur]);
  printStep();
}

void handleCommand(char* s) {
  while (*s == ' ' || *s == '\t') s++;
  if (*s == 0) {                       // bare Enter = next
    if (cur < NLEV - 1) gotoLevel(cur + 1);
    else Serial.println("  Last level. Type C for the arrays.");
    return;
  }

  char c = toupper(*s);
  const char* arg = s + 1;
  while (*arg == ' ' || *arg == '=') arg++;

  switch (c) {
    case 'M': {
      if (*arg == 0) { Serial.println("  Usage: M <eighths>, e.g. M 4"); break; }
      float e = atof(arg);
      if (e < 0 || e > 8) { Serial.println("  Eighths must be 0..8."); break; }
      obs[cur] = e;
      Serial.printf("  recorded: drive %.4g %%  ->  %.2f/8  %s\n",
                    LEVELS[cur], e, eighthsLabel(e));
      if (cur < NLEV - 1) gotoLevel(cur + 1);
      else { saveState(); Serial.println("  Last level. Type C for the arrays."); }
      break;
    }
    case 'E': {                        // E <drive> <eighths>
      char* end = nullptr;
      float d = strtod(arg, &end);
      if (!end || end == arg) { Serial.println("  Usage: E <drive%> <eighths>"); break; }
      while (*end == ' ' || *end == ',') end++;
      if (*end == 0) { Serial.println("  Usage: E <drive%> <eighths>"); break; }
      float e = atof(end);
      if (e < 0 || e > 8) { Serial.println("  Eighths must be 0..8."); break; }
      int i = nearestLevel(d);
      obs[i] = e;
      saveState();
      Serial.printf("  set: drive %.4g %%  ->  %.2f/8  %s%s\n",
                    LEVELS[i], e, eighthsLabel(e),
                    fabsf(LEVELS[i] - d) > 0.01f ? "   (nearest level)" : "");
      break;
    }
    case 'J': {
      if (*arg == 0) { Serial.println("  Usage: J <drive%>"); break; }
      gotoLevel(nearestLevel(atof(arg)));
      break;
    }
    case 'N':
      if (cur < NLEV - 1) gotoLevel(cur + 1);
      else Serial.println("  Last level. Type C for the arrays.");
      break;
    case 'B':
      if (cur > 0) gotoLevel(cur - 1);
      else Serial.println("  Already at the first level.");
      break;
    case 'R':
      applyDrive(activeDrive());
      Serial.println("  re-applied. Cycle the ignition to reseat the needle.");
      break;
    case 'S':
      if (*arg == 0) { Serial.println("  Usage: S <0-100>"); break; }
      freeDrive = constrain((float)atof(arg), 0.0f, 100.0f);
      saveState();                      // survives the ignition cycle
      applyDrive(freeDrive);
      Serial.println("  held. Cycle the ignition, let the needle settle, read it.");
      break;
    case 'T': printTable();  break;
    case 'C': printArrays(); break;
    case 'Z':
      for (int i = 0; i < NLEV; i++) obs[i] = NAN;
      Serial.println("  all recordings erased.");
      gotoLevel(0);
      break;
    case '?': printHelp(); break;
    default:  Serial.println("  ? for help"); break;
  }
}

void pollSerial() {
  static char buf[48];
  static uint8_t len = 0;
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\r') continue;
    if (ch == '\n') { buf[len] = 0; handleCommand(buf); len = 0; continue; }
    if (len < sizeof(buf) - 1) buf[len++] = ch;
  }
}

// ------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(400);

  prefs.begin("fuelcal", false);
  loadState();

  analogWriteResolution(FUEL_PWM_PIN, FUEL_PWM_BITS);
  // Re-assert the level before anything else: if an ignition cycle
  // rebooted us, the needle must return to where it was unprompted.
  applyDrive(activeDrive(), false);

  Serial.println();
  Serial.println("=================================================");
  Serial.println(" Freelander EV — fuel gauge calibration");
  Serial.printf ("   vactrol LED on GPIO%d, %d-bit PWM\n",
                 (int)FUEL_PWM_PIN, FUEL_PWM_BITS);
  Serial.printf ("   %d of %d levels recorded (saved in flash)\n",
                 recordedCount(), NLEV);
  Serial.println("=================================================");
  printHelp();
  applyDrive(activeDrive());
  printStep();
}

void loop() {
  pollSerial();

  // Re-assert the PWM once a second. Harmless, and it means a glitch on
  // the LEDC peripheral can never leave the needle stranded.
  static uint32_t last = 0;
  if (millis() - last > 1000) {
    last = millis();
    applyDrive(activeDrive(), false);
  }
}
