/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox.framework

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.support.annotation.UiThread
import android.support.annotation.VisibleForTesting
import android.support.annotation.VisibleForTesting.PRIVATE
import android.view.accessibility.AccessibilityManager

/**
 * A model to hold state related to the Android framework.
 */
class FrameworkRepo @VisibleForTesting(otherwise = PRIVATE) constructor() {

    private var wasInitCalled = false

    private val _isVoiceViewEnabled = MutableLiveData<Boolean>()
    val isVoiceViewEnabled: LiveData<Boolean> = _isVoiceViewEnabled

    /**
     * Initializes this repository.
     * @throws IllegalStateException when called twice.
     */
    fun init(accessibilityManager: AccessibilityManager) {
        if (wasInitCalled) throw IllegalStateException("FrameworkRepo.init unexpectedly called twice")
        wasInitCalled = true

        // We call the listener directly to set the initial state.
        val touchExplorationStateChangeListener = TouchExplorationStateChangeListener()
        accessibilityManager.addTouchExplorationStateChangeListener(touchExplorationStateChangeListener)
        touchExplorationStateChangeListener.onTouchExplorationStateChanged(accessibilityManager.isTouchExplorationEnabled)
    }

    private inner class TouchExplorationStateChangeListener : AccessibilityManager.TouchExplorationStateChangeListener {
        @UiThread // for simplicity: listener should be called from UI thread anyway.
        override fun onTouchExplorationStateChanged(isEnabled: Boolean) {
            _isVoiceViewEnabled.value = isEnabled // Touch exploration state == VoiceView.
        }
    }

    companion object {
        fun newInstanceAndInit(accessibilityManager: AccessibilityManager): FrameworkRepo {
            return FrameworkRepo().apply {
                init(accessibilityManager)
            }
        }
    }
}
