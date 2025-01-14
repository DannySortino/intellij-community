// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.navbar.ide.ui

import com.intellij.ide.actions.OpenInRightSplitAction
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.navbar.ide.actions.navBarContextMenuActionGroup
import com.intellij.platform.navbar.vm.NavBarPopupItem
import com.intellij.platform.navbar.vm.NavBarPopupVm
import com.intellij.ui.CollectionListModel
import com.intellij.ui.LightweightHint
import com.intellij.ui.PopupHandler
import com.intellij.ui.popup.HintUpdateSupply
import com.intellij.ui.speedSearch.ListWithFilter
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList

internal fun createNavBarPopup(list: JList<out NavBarPopupItem>): LightweightHint {
  HintUpdateSupply.installDataContextHintUpdateSupply(list)
  val popupComponent = list.withSpeedSearch()
  val popup = object : LightweightHint(popupComponent) {
    override fun onPopupCancel() {
      HintUpdateSupply.hideHint(list)
    }
  }
  popup.setFocusRequestor(popupComponent)
  popup.setForceShowAsPopup(true)
  popup.setCancelOnOtherWindowOpen(false)
  return popup
}

internal fun <T : NavBarPopupItem> navBarPopupList(
  vm: NavBarPopupVm<T>,
  contextComponent: Component,
  floating: Boolean,
): JList<T> {
  val list = ContextJBList<T>(contextComponent)
  list.model = CollectionListModel(vm.items)
  list.cellRenderer = NavBarPopupListCellRenderer(floating)
  list.border = JBUI.Borders.empty(5)
  list.background = JBUI.CurrentTheme.Popup.BACKGROUND
  list.addListSelectionListener {
    vm.itemsSelected(list.selectedValuesList)
  }
  PopupHandler.installPopupMenu(list, navBarContextMenuActionGroup(), ActionPlaces.NAVIGATION_BAR_POPUP)
  list.addMouseListener(object : MouseAdapter() {

    override fun mousePressed(e: MouseEvent) {
      if (!SystemInfo.isWindows) {
        click(e)
      }
    }

    override fun mouseReleased(e: MouseEvent) {
      if (SystemInfo.isWindows) {
        click(e)
      }
    }

    private fun click(e: MouseEvent) {
      if (!e.isPopupTrigger && e.clickCount == 1 && e.button == MouseEvent.BUTTON1) {
        vm.complete()
      }
    }
  })
  return list
}

private fun JList<out NavBarPopupItem>.withSpeedSearch(): JComponent {
  val wrapper = NavBarListWrapper(this)
  val component = ListWithFilter.wrap(this, wrapper) { item ->
    item.presentation.popupText ?: item.presentation.text
  } as ListWithFilter<*>
  wrapper.updateViewportPreferredSizeIfNeeded() // this fixes IDEA-301848 for some reason
  component.setAutoPackHeight(!UISettings.getInstance().showNavigationBarInBottom)
  OpenInRightSplitAction.overrideDoubleClickWithOneClick(component)
  return component
}
