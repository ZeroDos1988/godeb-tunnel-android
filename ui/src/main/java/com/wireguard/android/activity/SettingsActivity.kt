/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.wireguard.android.Application
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.preference.PreferencesPreferenceDataStore
import com.wireguard.android.service.TrustedNetworkMonitorService
import com.wireguard.android.util.AdminKnobs
import com.wireguard.android.util.TrustedEndpointDiscovery
import com.wireguard.android.util.UserKnobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interface for changing application-global persistent settings.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (supportFragmentManager.findFragmentById(android.R.id.content) == null) {
            supportFragmentManager.commit {
                add(android.R.id.content, SettingsFragment())
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        // Since this is pretty much abandoned by androidx, it never got updated for proper EdgeToEdge support,
        // which is enabled everywhere for API 35. So handle the insets manually here.
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val view = super.onCreateView(inflater, container, savedInstanceState)
            view.fitsSystemWindows = true
            return view
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, key: String?) {
            preferenceManager.preferenceDataStore = PreferencesPreferenceDataStore(lifecycleScope, Application.getPreferencesDataStore())
            addPreferencesFromResource(R.xml.preferences)
            preferenceScreen.initialExpandedChildrenCount = 8

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || QuickTileService.isAdded) {
                val quickTile = preferenceManager.findPreference<Preference>("quick_tile")
                quickTile?.parent?.removePreference(quickTile)
                --preferenceScreen.initialExpandedChildrenCount
            }
            if (AdminKnobs.disableConfigExport) {
                val zipExporter = preferenceManager.findPreference<Preference>("zip_exporter")
                zipExporter?.parent?.removePreference(zipExporter)
            }
            val wgQuickOnlyPrefs = arrayOf(
                preferenceManager.findPreference("tools_installer"),
                preferenceManager.findPreference("restore_on_boot"),
                preferenceManager.findPreference<Preference>("multiple_tunnels")
            ).filterNotNull()
            wgQuickOnlyPrefs.forEach { it.isVisible = false }
            lifecycleScope.launch {
                if (Application.getBackend() is WgQuickBackend) {
                    ++preferenceScreen.initialExpandedChildrenCount
                    wgQuickOnlyPrefs.forEach { it.isVisible = true }
                } else {
                    wgQuickOnlyPrefs.forEach { it.parent?.removePreference(it) }
                }
            }
            preferenceManager.findPreference<Preference>("log_viewer")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), LogViewerActivity::class.java))
                true
            }
            val kernelModuleEnabler = preferenceManager.findPreference<Preference>("kernel_module_enabler")
            if (WgQuickBackend.hasKernelSupport()) {
                lifecycleScope.launch {
                    if (Application.getBackend() !is WgQuickBackend) {
                        try {
                            withContext(Dispatchers.IO) { Application.getRootShell().start() }
                        } catch (_: Throwable) {
                            kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
                        }
                    }
                }
            } else {
                kernelModuleEnabler?.parent?.removePreference(kernelModuleEnabler)
            }

            preferenceManager.findPreference<ListPreference>("theme_mode")?.setOnPreferenceChangeListener { _, newValue ->
                Application.applyThemeMode(newValue as String)
                true
            }

            preferenceManager.findPreference<Preference>("smart_auto_disconnect")?.setOnPreferenceChangeListener { _, newValue ->
                TrustedNetworkMonitorService.syncWithPreference(requireContext().applicationContext, newValue as Boolean)
                true
            }

            val endpointPref = preferenceManager.findPreference<EditTextPreference>("trusted_endpoint_url")
            endpointPref?.setSummaryProvider {
                val value = (it as EditTextPreference).text
                if (value.isNullOrBlank()) getString(R.string.trusted_endpoint_url_summary)
                else value
            }
            endpointPref?.setOnPreferenceChangeListener { _, _ ->
                TrustedNetworkMonitorService.reevaluate(requireContext().applicationContext)
                true
            }

            preferenceManager.findPreference<Preference>("scan_trusted_endpoints")?.setOnPreferenceClickListener {
                scanTrustedEndpoints(endpointPref)
                true
            }

            lifecycleScope.launch {
                val bssids = UserKnobs.trustedBssids.first().joinToString(", ")
                preferenceManager.findPreference<EditTextPreference>("trusted_bssids_text")?.text =
                    if (bssids.isEmpty()) null else bssids
            }
        }

        private fun scanTrustedEndpoints(endpointPref: EditTextPreference?) {
            val ctx = requireContext()
            val progress = AlertDialog.Builder(ctx)
                .setMessage(R.string.scan_trusted_endpoints_scanning)
                .setCancelable(false)
                .create()
            progress.show()
            lifecycleScope.launch {
                try {
                    val found = withContext(Dispatchers.IO) { TrustedEndpointDiscovery.scan(ctx) }
                    progress.dismiss()
                    if (found.isEmpty()) {
                        AlertDialog.Builder(ctx)
                            .setMessage(R.string.scan_trusted_endpoints_none)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        return@launch
                    }
                    val labels = found.map { candidate ->
                        buildString {
                            append(candidate.url)
                            if (candidate.isGateway) {
                                append(" (")
                                append(getString(R.string.scan_trusted_endpoints_gateway))
                                append(')')
                            }
                        }
                    }.toTypedArray()
                    AlertDialog.Builder(ctx)
                        .setTitle(R.string.scan_trusted_endpoints_pick)
                        .setItems(labels) { _, which ->
                            val selected = found[which].url
                            endpointPref?.text = selected
                            lifecycleScope.launch {
                                UserKnobs.setTrustedEndpointUrl(selected)
                                TrustedNetworkMonitorService.reevaluate(ctx.applicationContext)
                            }
                            Toast.makeText(
                                ctx,
                                getString(R.string.scan_trusted_endpoints_saved, selected),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } catch (e: Throwable) {
                    progress.dismiss()
                    Toast.makeText(
                        ctx,
                        getString(R.string.scan_trusted_endpoints_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
