package com.ngohoang.testnav

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity

class MainActivity :
    AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var navText: TextView

    // ==========================================
    // CREATE
    // ==========================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        // ==========================================
        // STATUS
        // ==========================================

        statusText =
            TextView(this).apply {

                textSize = 18f

                setPadding(
                    20,
                    20,
                    20,
                    20
                )
            }

        // ==========================================
        // NAVIGATION
        // ==========================================

        navText =
            TextView(this).apply {

                textSize = 16f

                setPadding(
                    20,
                    20,
                    20,
                    20
                )

                text =
                    """
                    Navigation

                    Waiting for Google Maps...
                    """.trimIndent()
            }

        // ==========================================
        // NOTIFICATION ACCESS
        // ==========================================

        val notificationButton =
            Button(this).apply {

                text =
                    "Open Notification Access"

                setOnClickListener {

                    startActivity(
                        Intent(
                            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
                        )
                    )
                }
            }

        // ==========================================
        // ACCESSIBILITY
        // ==========================================

        val accessibilityButton =
            Button(this).apply {

                text =
                    "Open Accessibility Settings"

                setOnClickListener {

                    startActivity(
                        Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )
                    )
                }
            }

        // ==========================================
        // START MEDIA SERVICE
        // ==========================================

        val startServiceButton =
            Button(this).apply {

                text =
                    "Start Media Service"

                setOnClickListener {

                    startMediaSessionService()

                    updateStatus()
                }
            }

        // ==========================================
        // REFRESH
        // ==========================================

        val refreshButton =
            Button(this).apply {

                text =
                    "Refresh"

                setOnClickListener {

                    updateStatus()

                    updateNavigationUI(
                        NavStateManager.currentState
                    )
                }
            }

        // ==========================================
        // LAYOUT
        // ==========================================

        val layout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    20,
                    20,
                    20,
                    20
                )

                addView(
                    statusText
                )

                addView(
                    notificationButton
                )

                addView(
                    accessibilityButton
                )

                addView(
                    startServiceButton
                )

                addView(
                    refreshButton
                )

                addView(
                    navText
                )
            }

        val scrollView =
            ScrollView(this).apply {

                addView(
                    layout
                )
            }

        setContentView(
            scrollView
        )

        // ==========================================
        // INITIAL STATE
        // ==========================================

        updateStatus()

        updateNavigationUI(
            NavStateManager.currentState
        )
    }

    // ==========================================
    // START
    // ==========================================

    override fun onStart() {

        super.onStart()

        NavStateManager.addListener(
            navStateListener
        )

        updateStatus()
    }

    // ==========================================
    // STOP
    // ==========================================

    override fun onStop() {

        NavStateManager.removeListener(
            navStateListener
        )

        super.onStop()
    }

    // ==========================================
    // RESUME
    // ==========================================

    override fun onResume() {

        super.onResume()

        updateStatus()

        updateNavigationUI(
            NavStateManager.currentState
        )
    }

    // ==========================================
    // NAV STATE LISTENER
    // ==========================================

    private val navStateListener =
        object :
            NavStateManager.Listener {

            override fun onNavStateChanged(
                state: NavState
            ) {

                runOnUiThread {

                    updateNavigationUI(
                        state
                    )
                }
            }
        }

    // ==========================================
    // START MEDIA SERVICE
    // ==========================================

    private fun startMediaSessionService() {

        val intent =
            Intent(
                this,
                MediaSessionService::class.java
            )

        startService(
            intent
        )
    }

    // ==========================================
    // STATUS
    // ==========================================

    private fun updateStatus() {

        val notificationEnabled =
            isNotificationListenerEnabled()

        val accessibilityEnabled =
            isAccessibilityEnabled()

        statusText.text =
            """
            TestNav

            Notification Listener:
            ${
                if (
                    notificationEnabled
                )
                    "ENABLED"
                else
                    "DISABLED"
            }

            Google Maps Accessibility:
            ${
                if (
                    accessibilityEnabled
                )
                    "ENABLED"
                else
                    "DISABLED"
            }

            MediaSession:
            Managed by MediaSessionService
            """.trimIndent()
    }

    // ==========================================
    // NAV STATE UI
    // ==========================================

    private fun updateNavigationUI(
        state: NavState
    ) {

        navText.text =
            """
            ========================================
            NAVIGATION STATE
            ========================================

            Next road:
            ${state.nextRoad}

            Next distance:
            ${state.nextDistance}


            Instruction:
            ${state.instruction}

            Instruction distance:
            ${state.instructionDistance}


            Remaining distance:
            ${state.remainingDistance}

            Remaining time:
            ${state.remainingTime}

            ETA:
            ${state.eta}

            Speed:
            ${state.speed}

            ========================================
            MEDIA
            ========================================

            TITLE:
            ${state.instruction}

            ARTIST:
            ${state.nextRoad}

            ========================================
            """.trimIndent()
    }

    // ==========================================
    // NOTIFICATION LISTENER CHECK
    // ==========================================

    private fun isNotificationListenerEnabled():
            Boolean {

        val enabledListeners =
            Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            )
                ?: return false

        return enabledListeners
            .split(":")
            .any { component ->

                ComponentName
                    .unflattenFromString(
                        component
                    )
                    ?.packageName ==
                        packageName
            }
    }

    // ==========================================
    // ACCESSIBILITY CHECK
    // ==========================================

    private fun isAccessibilityEnabled():
            Boolean {

        val expectedComponent =
            ComponentName(
                this,
                MapsAccessibilityService::class.java
            )

        val enabledServices =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
                ?: return false

        return enabledServices
            .split(":")
            .any { component ->

                ComponentName
                    .unflattenFromString(
                        component
                    ) == expectedComponent
            }
    }
}