package com.gitlabmr.ui

import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.text.JTextComponent

/**
 * 让可编辑下拉的 UI 编辑器一定就位。
 *
 * 可编辑 JComboBox 的 editor 组件由 LAF 的 BasicComboBoxUI 缓存, getDisplaySize 会直接用它;
 * 某些时序下 "editable" 属性事件没能让缓存就位, 布局/显示时就 NPE
 * (DarculaComboBoxUI.getPreferredSize -> BasicComboBoxUI.getDisplaySize)。
 * 这里显式调用一次 addEditor, 保证缓存非空; UI 重装时 installComponents 也会自行调用。
 */
internal fun JComboBox<*>.ensureEditableEditor() {
    isEditable = true
    if (editor == null) return  // 编辑器尚未创建 (正常不会), 交给 LAF 后续安装
    (ui as? BasicComboBoxUI)?.addEditor()
}

/**
 * 可编辑下拉框 + 输入即时过滤。
 *
 * 不用平台的 ComboboxSpeedSearch: 它是"跳到匹配项/高亮", 不会把不匹配的项从下拉里拿掉,
 * 而这里要的是"输入 zhang, 下拉里只剩含 zhang 的候选"。
 *
 * 过滤不能在 DocumentListener 里直接改 model: 平台 UI 收到 model 变更会同步
 * configureEditor -> setText, 在文档通知期间修改文档会抛
 * IllegalStateException("Attempt to mutate in notification"); 缓存 editor 为空时则可能
 * 在后续布局中 NPE。因此这里把过滤延后一拍执行, 并始终以编辑器当前文本为准。
 */
class SearchableComboBox : JComboBox<String>() {

    private var allItems: List<String> = emptyList()
    private var guard = false
    private var filterPending = false

    init {
        ensureEditableEditor()
        val editorComponent = editor.editorComponent as? JTextComponent
        editorComponent?.let { ed ->
            ed.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = onTextChanged()
                override fun removeUpdate(e: DocumentEvent) = onTextChanged()
                override fun changedUpdate(e: DocumentEvent) = onTextChanged()
            })
            ed.addFocusListener(object : FocusAdapter() {
                // 失焦恢复全量: 下次点开下拉能看到所有候选, 而不是上次过滤后的残留
                override fun focusLost(e: FocusEvent) = applyModel(allItems, currentText(), showPopup = false)
            })
        }
    }

    /** 重装候选 (全量), 保留用户当前输入; 成员列表后台回来后调用 */
    fun setAllItems(items: List<String>) {
        allItems = items
        applyModel(items, currentText(), showPopup = false)
    }

    /** 按 username 带入候选的完整展示串 (候选里没有则原样使用) */
    fun setUsername(username: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        val match = allItems.firstOrNull { it == name || it.startsWith("$name (") }
        applyModel(allItems, match ?: name, showPopup = false)
    }

    /** 用户最终输入: 候选里有就返回完整展示串, 没有也原样返回 (按 username 提交的兜底语义) */
    fun currentText(): String = (editor.item as? String).orEmpty().trim()

    /** Swing 自带的"按首字符跳到匹配项"会覆盖正在输入的文本, 直接关掉 */
    override fun selectWithKeyChar(keyChar: Char): Boolean = false

    private fun selectedItemText(): String? =
        if (selectedIndex in 0 until itemCount) getItemAt(selectedIndex) else null

    private fun onTextChanged() {
        if (guard || filterPending) return
        val typed = currentText()
        if (typed == selectedItemText()) return  // 选中项回填编辑器, 不是输入

        filterPending = true
        SwingUtilities.invokeLater {
            filterPending = false
            if (guard) return@invokeLater
            // 已失焦 (用户输完就点别处): 不再过滤, 保持全量候选
            if (editor.editorComponent?.hasFocus() != true) return@invokeLater
            val current = currentText()
            if (current == selectedItemText()) return@invokeLater
            val filtered = if (current.isEmpty()) {
                allItems
            } else {
                allItems.filter { it.contains(current, ignoreCase = true) }
            }
            val showPopup = current.isNotEmpty() && filtered.isNotEmpty()
            applyModel(filtered, current, showPopup)
        }
    }

    private fun applyModel(items: List<String>, text: String, showPopup: Boolean) {
        guard = true
        try {
            model = DefaultComboBoxModel(items.toTypedArray())
            editor.item = text  // 必须回填, 否则会被选中项冲掉
        } finally {
            guard = false
        }
        if (showPopup) isPopupVisible = true
    }
}
