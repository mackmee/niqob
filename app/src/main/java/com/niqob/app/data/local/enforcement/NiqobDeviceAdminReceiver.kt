package com.niqob.app.data.local.enforcement

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Android Device Admin Receiver declaration.
 *
 * This receiver is registered in the Android manifest to support managed-device
 * and enterprise provisioning on dedicated NIQOB child hardware.
 *
 * SAFETY GUARDRAIL:
 * - This receiver is inert until explicitly provisioned by parent/enterprise setup.
 * - It NEVER provisions itself automatically.
 */
class NiqobDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
