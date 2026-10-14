package com.landroverdash.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Base for every Wattson screen: full-screen (immersive) on the head unit, and
 * the shared side rail.
 *
 * Navigation keeps at most ONE screen on top of the dashboard. The dashboard
 * owns the Bluetooth link and must never be finished by the rail, so:
 *   - from the dashboard, a rail tap starts the target screen on top;
 *   - from any other screen, it starts the target and finishes itself, and
 *     "Drive" simply finishes back to the dashboard underneath.
 */
abstract class WattsonActivity : AppCompatActivity() {

    enum class Screen { DRIVE, MODES, ENERGY, BATTERY, TERRAIN, VEHICLE, SETTINGS }

    companion object {
        /** Hide the status and navigation bars. Swipe from an edge to show them. */
        const val IMMERSIVE = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the keyboard and a swipe all bring the bars back; hide them
        // again whenever this screen regains focus.
        if (hasFocus) applyImmersive()
    }

    protected fun applyImmersive() {
        if (!IMMERSIVE) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    protected fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** Highlight [current] in the rail and wire every item. */
    protected fun bindNavRail(current: Screen) {
        val items = mapOf(
            Screen.DRIVE    to R.id.navDrive,
            Screen.MODES    to R.id.navModes,
            Screen.ENERGY   to R.id.navEnergy,
            Screen.TERRAIN  to R.id.navTerrain,
            Screen.VEHICLE  to R.id.navVehicle
        )
        // Battery and Settings have no rail item: they open from Vehicle, so
        // Vehicle stays lit (tapping it goes back there), and Back returns there
        // too rather than all the way to Drive.
        val underVehicle = current == Screen.BATTERY || current == Screen.SETTINGS
        val lit = if (underVehicle) Screen.VEHICLE else current
        for ((screen, id) in items) {
            val item = findViewById<View>(id) ?: continue
            item.isSelected = screen == lit
            item.setOnClickListener { navigateTo(screen, current) }
        }
        if (underVehicle) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateTo(Screen.VEHICLE, current)
            })
        }
        observeHealthBadge()
    }

    /** When this screen last navigated, to swallow a double tap. */
    private var lastNavAt = 0L

    /** Open [target] from [current], following the one-screen-on-top rule. */
    protected fun navigateTo(target: Screen, current: Screen) {
        if (target == current) return
        // A second tap before the first screen has opened would start a
        // second copy, or finish this one twice, and break the rule.
        val now = android.os.SystemClock.elapsedRealtime()
        if (isFinishing || now - lastNavAt < 700L) return
        lastNavAt = now
        if (current == Screen.DRIVE) {
            startActivity(intentFor(target))
            noTransition()
            return
        }
        if (target != Screen.DRIVE) startActivity(intentFor(target))
        finish()
        noTransition()
    }

    private fun intentFor(target: Screen): Intent = Intent(this, when (target) {
        Screen.DRIVE    -> DashboardActivity::class.java
        Screen.MODES    -> ModesActivity::class.java
        Screen.ENERGY   -> EnergyChartActivity::class.java
        Screen.BATTERY  -> BatteryActivity::class.java
        Screen.TERRAIN  -> ElevationActivity::class.java
        Screen.VEHICLE  -> DetailsActivity::class.java
        Screen.SETTINGS -> SettingsActivity::class.java
    })

    /** Screens swap instantly — a slide animation is just lag in a car. */
    @Suppress("DEPRECATION")
    protected fun noTransition() = overridePendingTransition(0, 0)

    /**
     * Link status for screens that don't own the connection: polled, because
     * VehicleLink.service can appear or change while the screen is open (the
     * dashboard underneath reconnects on its own). [onChange] runs on the main
     * thread whenever the state flips, and once at the start.
     */
    protected fun observeLink(dot: View?, label: android.widget.TextView?,
                              onChange: (Boolean) -> Unit = {}) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var last: Boolean? = null
                while (true) {
                    val up = VehicleLink.service?.isConnected() == true
                    if (up != last) {
                        last = up
                        dot?.backgroundTintList = ColorStateList.valueOf(
                            color(if (up) R.color.accent else R.color.text3))
                        label?.text = if (up) "Connected" else "Not connected"
                        onChange(up)
                    }
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }
    }

    /** Amber or red dot on the Vehicle item whenever health isn't normal. */
    private fun observeHealthBadge() {
        val badge = findViewById<View>(R.id.navVehicleBadge) ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Health.state.collect { h ->
                    badge.visibility = if (h.level == Health.Level.OK) View.GONE else View.VISIBLE
                    badge.backgroundTintList = ColorStateList.valueOf(
                        color(if (h.level == Health.Level.FAULT) R.color.crit else R.color.warn))
                }
            }
        }
    }
}
