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
                applyLookIn(typedText());
            }
        });
    }

    /** 那一格里此刻的文本：下拉选中的目录与敲进去的路径都从这一条出来。 */
    private String typedText() {
        Object editor = lookIn == null || lookIn.getEditor() == null
                ? null : lookIn.getEditor().getItem();
        if (editor == null) {
            Object item = lookIn == null ? null : lookIn.getSelectedItem();
            return item == null ? "" : String.valueOf(item);
        }
        return String.valueOf(editor);
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
