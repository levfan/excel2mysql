package com.excel2mysql.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JToolBar;

/**
 * 两处加强，都是为了"路径是我敲进来的"这一条现场习惯：
 *
 * <p>一、确认选择时若选中项是目录，就进入该目录并留在窗口里，绝不把目录当文件交出去。
 * 文件名框里粘带引号的路径（资源管理器「复制文件地址」的格式）时，JDK 8 的
 * BasicFileChooserUI 会跳过目录判断直接确认，这里补上最后一道。
 *
 * <p>二、顶上那一格「查找范围」在 JDK8 的 Windows 外观里是只出不进的下拉
 * （{@code WindowsFileChooserUI$DirectoryComboBoxModel} 只收 File，敲进去的字符串
 * 会当场抛 ClassCastException），所以这里把它接过来自己管：拆掉外观挂的那个
 * ActionListener、开可编辑，敲目录回车就进去，敲文件回车就进它的父目录并把这个文件
 * 加进已选清单，下拉里选的目录照旧能跳。找不到这一格（换 JDK 或换外观）就保持原样，
 * 只在日志里说一句，不把选择器弄坏。
 *
 * <p>三、接过这一格之后，导航与手敲必须分开走。左侧「此电脑」「网络」「库」这些是
 * shell 虚拟节点，模型里放的是 {@code Win32ShellFolder2}（File 的子类）：{@code toString()}
 * 是显示名「此电脑」，{@code getPath()} 却是 CLSID {@code ::{20D04FE0-...}}，磁盘上没有
 * 叫「此电脑」的目录。原先把选中项一律拍成字符串再 {@code new File} 去解析，点「此电脑」
 * 就必然落空并弹「找不到这个目录」。现在导航（选中项与当前目录不同）直接把 File 对象
 * 交给 {@code setCurrentDirectory}，只有当前目录没变、编辑器里是新文本时才算手敲，
 * 才走字符串解析与「找不到」提示。
 */
public final class FolderAwareChooser extends JFileChooser {

    /** 敲出来的路径找不到时的提示出口：自检换成收集器，生产走系统提示框。 */
    public interface Note {
        void show(java.awt.Component parent, String text);
    }

    private static final Note DEFAULT_NOTE = new Note() {
        @Override
        public void show(java.awt.Component parent, String text) {
            JOptionPane.showMessageDialog(parent, text, "打不开这个目录",
                    JOptionPane.WARNING_MESSAGE);
        }
    };

    private static Note noteSink = DEFAULT_NOTE;

    private JComboBox lookIn;
    private boolean wired;
    /** 防重入：navigateTo 里回写选中项可能再触发一次动作。 */
    private boolean inLookIn;

    /** 自检用：把提示接到一个收集器上；传 null 恢复成真的弹提示框。 */
    public static void setNoteSink(Note sink) {
        noteSink = sink == null ? DEFAULT_NOTE : sink;
    }

    @Override
    public void approveSelection() {
        File dir = firstDirectory(getSelectedFiles());
        if (dir == null && getSelectedFile() != null && getSelectedFile().isDirectory()) {
            dir = getSelectedFile();
        }
        if (dir == null) {
            super.approveSelection();
            return;
        }
        setSelectedFile(null);
        setSelectedFiles(null);
        setCurrentDirectory(dir);
    }

    /**
     * 摆上屏幕时才有完整的组件树，所以这一层接线放在这里而不是构造函数里。
     *
     * <p>{@code showOpenDialog} 每次都会把选择器挂进一个新容器，addNotify 也就每次走一遍；
     * 已经接过一次就不再来一遍，免得挂两份监听。
     */
    @Override
    public void addNotify() {
        super.addNotify();
        if (!wired) {
            wireLookIn();
        }
    }

    /** 找到「查找范围」那一格，换成可编辑并由本类接管回车与下拉选择。 */
    private void wireLookIn() {
        lookIn = findLookIn(this);
        if (lookIn == null) {
            System.out.println("! [WARN] GUI | 没找到文件选择框的「查找范围」那一格，"
                    + "这一格保持原样（只能下拉选目录，敲路径回车不跳）");
            return;
        }
        wired = true;
        for (java.awt.event.ActionListener al : lookIn.getActionListeners()) {
            lookIn.removeActionListener(al);
        }
        lookIn.setEditable(true);
        lookIn.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                onLookInAction();
            }
        });
    }

    /**
     * 「查找范围」的动作分两路，判据是"选中项有没有跟着动"。
     *
     * <p>导航（下拉选目录、点左侧「此电脑」「网络」「库」）时选中项与当前目录不同，
     * 直接拿 File 对象交给 {@code setCurrentDirectory}——虚拟节点也一样能展开，
     * 因为 {@code Win32ShellFolder2} 的 {@code isDirectory()} 是真的 true。这一路
     * 无论跳没跳成都不落回字符串解析，免得对着一个能打开的节点弹「找不到」。
     *
     * <p>手敲路径回车时选中项没动（仍是原目录），编辑器里是新文本，照旧走解析：
     * 敲对了就进，敲错了仍然要有一句可见提示。
     */
    private void onLookInAction() {
        if (inLookIn) {
            return;
        }
        inLookIn = true;
        try {
            File nav = navigatedFile();
            if (nav != null && !sameDir(nav, getCurrentDirectory())) {
                if (!navigateTo(nav)) {
                    restoreLookIn();
                }
                return;
            }
            applyLookIn(typedText());
        } finally {
            inLookIn = false;
        }
    }

    /**
     * 这次动作是不是在导航：选中项或编辑器里挂着 File 对象就算导航。
     *
     * <p>两处都要看。下拉选中时 {@code getSelectedItem()} 是 File；可点「此电脑」这类
     * 不在下拉模型里的节点时，{@code DirectoryComboBoxModel.setSelectedItem} 匹配不到项会
     * 让 {@code getSelectedItem()} 返回 null，只有编辑器里挂着那个 File 对象。只看前一处
     * 就会漏到字符串解析，重新弹「找不到这个目录」。
     *
     * <p>手敲路径回车时两处都是字符串，返回 null，照旧走解析。
     */
    private File navigatedFile() {
        Object[] candidates = new Object[] {
            lookIn == null ? null : lookIn.getSelectedItem(),
            rawEditorItem(),
        };
        for (Object o : candidates) {
            if (o instanceof File) {
                return (File) o;
            }
        }
        return null;
    }

    /**
     * 拿 File 对象直接跳，全程不做字符串化，虚拟节点与普通目录走同一条路。
     * 跳不动返回 false，由调用方决定是静默落回还是继续按文本试。
     */
    private boolean navigateTo(File dir) {
        try {
            if (dir == null || !dir.isDirectory()) {
                return false;
            }
            setCurrentDirectory(dir);
            rescanCurrentDirectory();
            if (lookIn != null) {
                lookIn.setSelectedItem(dir);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 两处是否同一目录；虚拟节点拿不到 canonical，退回比 getPath。 */
    private static boolean sameDir(File a, File b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.equals(b)) {
            return true;
        }
        String pa = a.getPath();
        String pb = b.getPath();
        if (pa != null && pa.equalsIgnoreCase(pb)) {
            return true;
        }
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Exception e) {
            return false;
        }
    }

    /** 编辑器里那个东西的原始对象：下拉选中时是 File，手敲时是 String。 */
    private Object rawEditorItem() {
        return lookIn == null || lookIn.getEditor() == null
                ? null : lookIn.getEditor().getItem();
    }

    /** 那一格里此刻的文本：下拉选中的目录与敲进去的路径都从这一条出来。 */
    private String typedText() {
        Object item = rawEditorItem();
        if (item == null) {
            Object selected = lookIn == null ? null : lookIn.getSelectedItem();
            return selected == null ? "" : String.valueOf(selected);
        }
        return String.valueOf(item);
    }

    /**
     * 把一段路径文本落到它该去的地方，返回最后停在哪一层的绝对路径。
     *
     * <p>目录就进去；文件就进它的父目录并把这个文件加进已选清单（这两处都是多选框，
     * 敲一个文件等于"我要它"）；找不到就一句提示，当前目录不动——静默不跳等于没反应。
     */
    String applyLookIn(String raw) {
        String text = unquote(raw == null ? "" : raw.trim());
        if (text.isEmpty()) {
            return getCurrentDirectory().getAbsolutePath();
        }
        File target = resolve(text);
        if (target == null) {
            // 虚拟节点（此电脑/网络/库）的 toString 是显示名，磁盘上没这个路径，
            // resolve 必然落空；但它就在下拉候选里，按显示名找回来直接跳。
            File alias = matchLookInItem(text);
            if (alias != null && alias.isDirectory()) {
                setCurrentDirectory(alias);
                rescanCurrentDirectory();
                if (lookIn != null) {
                    lookIn.setSelectedItem(alias);
                }
                return alias.getAbsolutePath();
            }
            noteSink.show(this, "找不到这个目录或文件：" + text
                    + "\n「查找范围」里可以敲目录或文件的完整路径，按回车跳转。");
            restoreLookIn();
            return getCurrentDirectory().getAbsolutePath();
        }
        if (target.isDirectory()) {
            setCurrentDirectory(target);
            if (lookIn != null) {
                lookIn.setSelectedItem(target);
            }
            return target.getAbsolutePath();
        }
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            noteSink.show(this, "打不开这个路径的所在目录：" + text);
            restoreLookIn();
            return getCurrentDirectory().getAbsolutePath();
        }
        setCurrentDirectory(parent);
        addToSelection(target);
        if (lookIn != null) {
            lookIn.setSelectedItem(parent);
        }
        return parent.getAbsolutePath();
    }

    /** 提示完把那一格落回真正在的这一层，别留一段跳不过去的文本在那儿。 */
    private void restoreLookIn() {
        if (lookIn != null) {
            lookIn.setSelectedItem(getCurrentDirectory());
        }
    }

    /**
     * 在下拉候选里按显示名或路径找一项。
     *
     * <p>给 shell 虚拟节点留的兜底：`此电脑` 的 {@code getPath()} 是 CLSID、{@code toString()}
     * 是显示名，两者都当不了磁盘路径，但它们确实在下拉模型里，且 {@code isDirectory()} 为 true。
     * 手敲文本永远碰不到这里（模型里没有同名项），所以不会把"打错路径"误判成"能打开"。
     */
    private File matchLookInItem(String text) {
        if (lookIn == null || lookIn.getModel() == null || text.isEmpty()) {
            return null;
        }
        javax.swing.ComboBoxModel model = lookIn.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            Object o = model.getElementAt(i);
            if (o instanceof File) {
                File f = (File) o;
                if (text.equals(f.toString()) || text.equals(f.getPath())) {
                    return f;
                }
            }
        }
        return null;
    }

    /** 相对路径按当前目录解析；不存在一律返回 null，不拿半截路径去跳。 */
    private File resolve(String text) {
        File f = new File(text);
        if (!f.isAbsolute()) {
            f = new File(getCurrentDirectory(), text);
        }
        try {
            File canonical = f.getCanonicalFile();
            return canonical.exists() ? canonical : null;
        } catch (java.io.IOException e) {
            return f.exists() ? f.getAbsoluteFile() : null;
        }
    }

    /** 资源管理器「复制文件地址」给的是带引号的一串，粘进来要先脱掉。 */
    private static String unquote(String text) {
        String s = text;
        while (s.length() > 1 && (s.startsWith("\"") || s.startsWith("'"))
                && (s.endsWith("\"") || s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** 多选语义：已经选上的先留着，同一路径不重复加。 */
    private void addToSelection(File f) {
        List<File> keep = new ArrayList<File>();
        File[] current = getSelectedFiles();
        if (current != null) {
            for (File c : current) {
                if (c != null && !samePath(c, f)) {
                    keep.add(c);
                }
            }
        }
        keep.add(f);
        setSelectedFiles(keep.toArray(new File[keep.size()]));
        setSelectedFile(f);
    }

    private static boolean samePath(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (java.io.IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** 自检用：这一格现在接不接受输入（没找到那一格时是 false）。 */
    public boolean lookInEditable() {
        return wired && lookIn != null && lookIn.isEditable();
    }

    /**
     * 自检用：不摆上屏幕也先把那一格接好。
     *
     * 真入口是 addNotify（showOpenDialog 挂进容器时必然走），这里只是把同一个函数提前调一次，
     * 让自检能在不弹模态窗口的情况下验到同一条接线。
     */
    public void wireForSelftest() {
        if (!wired) {
            wireLookIn();
        }
    }

    /** 自检用：把文本填进「查找范围」，等同于敲完按回车。 */
    public void typeLookIn(String text) {
        if (lookIn != null && lookIn.getEditor() != null) {
            lookIn.getEditor().setItem(text);
        }
        applyLookIn(text);
    }

    /** 自检用：这一格下拉里此刻的候选目录，含「此电脑」这类 shell 虚拟节点。 */
    public Object[] lookInItems() {
        if (lookIn == null || lookIn.getModel() == null) {
            return new Object[0];
        }
        javax.swing.ComboBoxModel model = lookIn.getModel();
        Object[] out = new Object[model.getSize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = model.getElementAt(i);
        }
        return out;
    }

    /**
     * 自检用：把选中项换成给定对象再跑一次动作，等同于点它。
     * 点「此电脑」「网络」「库」走的就是这条路——不能弹「找不到」。
     */
    public void pickLookIn(Object item) {
        if (lookIn != null) {
            lookIn.setSelectedItem(item);
        }
        onLookInAction();
    }

    /** 工具条上那个下拉就是它；找不到返回 null，调用方自己保持原样。 */
    private static JComboBox findLookIn(java.awt.Container root) {
        for (java.awt.Component k : root.getComponents()) {
            if (k instanceof JToolBar) {
                for (java.awt.Component t : ((java.awt.Container) k).getComponents()) {
                    if (t instanceof JComboBox) {
                        return (JComboBox) t;
                    }
                }
            }
            if (k instanceof java.awt.Container) {
                JComboBox got = findLookIn((java.awt.Container) k);
                if (got != null) {
                    return got;
                }
            }
        }
        return null;
    }

    private static File firstDirectory(File[] files) {
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f != null && f.isDirectory()) {
                return f;
            }
        }
        return null;
    }
}
