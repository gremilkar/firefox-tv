/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.tv.firefox

import android.content.Context
import android.support.v4.app.FragmentManager
import android.text.TextUtils
import io.sentry.Sentry
import mozilla.components.browser.session.Session
import org.mozilla.tv.firefox.webrender.WebRenderFragment
import org.mozilla.tv.firefox.navigationoverlay.NavigationOverlayFragment
import org.mozilla.tv.firefox.pocket.PocketVideoFragment
import org.mozilla.tv.firefox.settings.SettingsFragment
import org.mozilla.tv.firefox.telemetry.MenuInteractionMonitor
import org.mozilla.tv.firefox.telemetry.TelemetryIntegration
import org.mozilla.tv.firefox.telemetry.UrlTextInputLocation
import org.mozilla.tv.firefox.utils.URLs
import org.mozilla.tv.firefox.utils.UrlUtils
import org.mozilla.tv.firefox.widget.InlineAutocompleteEditText
import java.lang.IllegalStateException

class ScreenController {
    var currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY
        get

    // TODO cache overlay fragment so that custom pinned sites don't fade in every time the menu is opened

    /**
     * We add all the fragments at start instead of creating them when needed in order to make the assumption
     * that all Fragments exist.
     * To show the correct Fragment, we use Fragment hide/show to make sure the correct Fragment is visible.
     * We DO NOT use the Fragment backstack so that all transitions are controlled in the same manner, and we
     * don't end up mixing backstack actions with show/hide.
     */
    fun setUpFragmentsForNewSession(fragmentManager: FragmentManager, session: Session) {
        val renderFragment = WebRenderFragment.createForSession(session)
        val pocketFragment = PocketVideoFragment()
        val settingsFragment = SettingsFragment()
        fragmentManager
            .beginTransaction()
            .add(R.id.container_settings, settingsFragment, SettingsFragment.FRAGMENT_TAG)
            .add(R.id.container_pocket, pocketFragment, PocketVideoFragment.FRAGMENT_TAG)
            .add(R.id.container_web_render, renderFragment, WebRenderFragment.FRAGMENT_TAG)
            // We add NavigationOverlayFragment last so that it takes focus
            .add(R.id.container_navigation_overlay, NavigationOverlayFragment(), NavigationOverlayFragment.FRAGMENT_TAG)
            .hide(renderFragment) // TODO note that this will need to be changed in order to display WebRenderFragment under a split overlay
            .hide(pocketFragment)
            .hide(settingsFragment)
            .commitNow()

        if (currentActiveScreen != ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY) {
            Sentry.capture(
                    IllegalStateException("Inconsistent state, expected " +
                            "${ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY.name}," +
                            " got ${currentActiveScreen.name}"))
        }
    }

    /**
     * Loads the given url. If isTextInput is true, there should be no null parameters.
     */
    fun onUrlEnteredInner(
        context: Context,
        fragmentManager: FragmentManager,
        urlStr: String,
        isTextInput: Boolean,
        autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?,
        inputLocation: UrlTextInputLocation?
    ) {
        if (TextUtils.isEmpty(urlStr.trim())) {
            return
        }

        val isUrl = UrlUtils.isUrl(urlStr)
        val updatedUrlStr = if (isUrl) UrlUtils.normalize(urlStr) else UrlUtils.createSearchUrl(context, urlStr)

        showBrowserScreenForUrl(fragmentManager, updatedUrlStr)

        if (isTextInput) {
            // Non-text input events are handled at the source, e.g. home tile click events.
            if (autocompleteResult == null) {
                throw IllegalArgumentException("Expected non-null autocomplete result for text input")
            }
            if (inputLocation == null) {
                throw IllegalArgumentException("Expected non-null input location for text input")
            }

            TelemetryIntegration.INSTANCE.urlBarEvent(isUrl, autocompleteResult, inputLocation)
        }
    }

    fun showSettingsScreen(fragmentManager: FragmentManager) {
        handleTransitionAndUpdateActiveScreen(fragmentManager, ScreenControllerStateMachine.Transition.ADD_SETTINGS)
    }

    fun showPocketScreen(fragmentManager: FragmentManager) {
        handleTransitionAndUpdateActiveScreen(fragmentManager, ScreenControllerStateMachine.Transition.ADD_POCKET)
    }

    fun showBrowserScreenForCurrentSession(fragmentManager: FragmentManager, session: Session) {
        if (session.url != URLs.APP_URL_HOME) {
            handleTransitionAndUpdateActiveScreen(fragmentManager, ScreenControllerStateMachine.Transition.SHOW_BROWSER)
        }
    }

    fun showBrowserScreenForUrl(fragmentManager: FragmentManager, url: String) {
        // The browser Fragment will always be available because we create it when creating a session,
        // and use the FragmentManager to hide it when it's not used.
        handleTransitionAndUpdateActiveScreen(fragmentManager, ScreenControllerStateMachine.Transition.SHOW_BROWSER)
        val webRenderFragment = fragmentManager.webRenderFragment()
        webRenderFragment.loadUrl(url)
    }

    // TODO: handle about:home and close overlay by button press separately
    fun showNavigationOverlay(fragmentManager: FragmentManager?, toShow: Boolean) {
        fragmentManager ?: return
        fragmentManagerShowNavigationOverlay(fragmentManager, toShow)
        currentActiveScreen = if (toShow) ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY
                else ScreenControllerStateMachine.ActiveScreen.WEB_RENDER
    }

    private fun fragmentManagerShowNavigationOverlay(fragmentManager: FragmentManager, toShow: Boolean) {
        var transaction = fragmentManager.beginTransaction()
        val overlayFragment = fragmentManager.navigationOverlayFragment()
        val renderFragment = fragmentManager.webRenderFragment()

        if (toShow) {
            transaction = transaction.show(overlayFragment)
                // TODO note that hiding WebRenderFragment will not be possible under a split overlay
                .hide(renderFragment)
            MenuInteractionMonitor.menuOpened()
        } else {
            transaction = transaction.hide(overlayFragment)
                .show(renderFragment)
            MenuInteractionMonitor.menuClosed()
        }
        transaction.commit()
    }

    fun handleBack(fragmentManager: FragmentManager): Boolean {
        val webRenderFragment = fragmentManager.webRenderFragment()
        if (currentActiveScreen == ScreenControllerStateMachine.ActiveScreen.WEB_RENDER) {
            if (webRenderFragment.onBackPressed()) return true
        }
        val transition = ScreenControllerStateMachine.getNewStateBackPress(currentActiveScreen, isOnHomeUrl(fragmentManager))
        return handleTransitionAndUpdateActiveScreen(fragmentManager, transition)
    }

    fun handleMenu(fragmentManager: FragmentManager) {
        val transition = ScreenControllerStateMachine.getNewStateMenuPress(currentActiveScreen, isOnHomeUrl(fragmentManager))
        handleTransitionAndUpdateActiveScreen(fragmentManager, transition)
    }

    private fun isOnHomeUrl(fragmentManager: FragmentManager): Boolean {
        val webRenderFragment = fragmentManager.webRenderFragment()
        return webRenderFragment.session.url == URLs.APP_URL_HOME
    }

    private fun handleTransitionAndUpdateActiveScreen(fragmentManager: FragmentManager, transition: ScreenControllerStateMachine.Transition): Boolean {
        // Call show() before hide() so that focus moves correctly to the shown fragment once others are hidden
        when (transition) {
            ScreenControllerStateMachine.Transition.ADD_OVERLAY -> {
                fragmentManagerShowNavigationOverlay(fragmentManager, true)
                currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY
            }
            ScreenControllerStateMachine.Transition.REMOVE_OVERLAY -> {
                showNavigationOverlay(fragmentManager, false)
                currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.WEB_RENDER
            }
            ScreenControllerStateMachine.Transition.ADD_POCKET -> {
                fragmentManager.beginTransaction()
                    .show(fragmentManager.pocketFragment())
                    .hide(fragmentManager.navigationOverlayFragment())
                    .commit()
                currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.POCKET
            }
            ScreenControllerStateMachine.Transition.REMOVE_POCKET -> {
                fragmentManager.beginTransaction()
                    .show(fragmentManager.navigationOverlayFragment())
                    .hide(fragmentManager.pocketFragment())
                    .commit()
                currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY
            }
            ScreenControllerStateMachine.Transition.ADD_SETTINGS -> {
                fragmentManager.beginTransaction()
                    .show(fragmentManager.settingsFragment())
                    .hide(fragmentManager.navigationOverlayFragment())
                    .commit()
                currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.SETTINGS
            }
            ScreenControllerStateMachine.Transition.REMOVE_SETTINGS -> {
                fragmentManager.beginTransaction()
                    .show(fragmentManager.navigationOverlayFragment())
                    .hide(fragmentManager.settingsFragment())
                    .commit()
                currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.NAVIGATION_OVERLAY
            }
            ScreenControllerStateMachine.Transition.SHOW_BROWSER -> {
                fragmentManager.beginTransaction()
                    .show(fragmentManager.webRenderFragment())
                    .hide(fragmentManager.navigationOverlayFragment())
                    .hide(fragmentManager.pocketFragment())
                    .hide(fragmentManager.settingsFragment())
                    .commit()
                currentActiveScreen = ScreenControllerStateMachine.ActiveScreen.WEB_RENDER
            }
            ScreenControllerStateMachine.Transition.EXIT_APP -> { return false }
            ScreenControllerStateMachine.Transition.NO_OP -> { return true }
        }
        return true
    }
}

private fun FragmentManager.webRenderFragment(): WebRenderFragment =
    this.findFragmentByTag(WebRenderFragment.FRAGMENT_TAG) as WebRenderFragment

private fun FragmentManager.navigationOverlayFragment(): NavigationOverlayFragment =
    this.findFragmentByTag(NavigationOverlayFragment.FRAGMENT_TAG) as NavigationOverlayFragment

private fun FragmentManager.pocketFragment(): PocketVideoFragment =
    this.findFragmentByTag(PocketVideoFragment.FRAGMENT_TAG) as PocketVideoFragment

private fun FragmentManager.settingsFragment(): SettingsFragment =
    this.findFragmentByTag(SettingsFragment.FRAGMENT_TAG) as SettingsFragment
