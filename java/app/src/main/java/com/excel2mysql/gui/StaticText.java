package com.excel2mysql.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.FocusTraversalPolicy;
import java.awt.Insets;

import javax.swing.JTextField;

/**
 * 界面上"只是说一句字"的那一类文字：拖得动选区、Ctrl+C 抄得走，但 Tab 不在它这里停。
 *
 * <p>为什么不用 JLabel：它一个字也选不了。沿用 {@link HintLabel} 那一招——不可编辑、
 * 无边框、不画底色的 JTextField，拖选、高亮、复制都是这套控件现成的。
 *
 * <p>为什么不重写 {@code isFocusTraversable()}：实测（JDK8 + 系统外观 + 真按键）在
 * java.awt.Component 上把它改成 false，会连带 {@code isFocusable()} 也变成 false，
 * 件从此拿不到焦点——Ctrl+C 那条就废了，"不进 Tab 环"付的代价比它本身还大。
 * Tab 环改由 {@link #policySkipping} 那层包装策略绕开：焦点仍然落得进来（鼠标点它），
 * 但环里每个真控件的"下一站"都不会是它。
 */
public class StaticText extends JTextField {

    private static final Insets NO_MARGIN = new Insets(0, 0, 0, 0);

    public StaticText(String text) {
        super(text);
        setEditable(false);
        setBorder(null);
        setMargin(NO_MARGIN);
        // 不画底色才不像一块输入框，看着仍是界面上的一行说明
        setOpaque(false);
        setFocusable(true);
        setRequestFocusEnabled(true);
    }

    /**
     * 把窗口现有的 Tab 策略包一层：环里走到静态文字就继续往下走。
     *
     * <p>基策略原样委托，只多这一步跳过——控件之间的先后次序一个字都没改，
     * 主窗口建好时套一次即可（弹窗没这层，那些窗里的件照旧可 Tab 到）。
     */
    public static FocusTraversalPolicy policySkipping(FocusTraversalPolicy base) {
        return new SkipPolicy(base);
    }

    /** 这条策略是不是"绕开静态文字"的那一层：自检判主窗口真装上了它，不靠类名猜。 */
    public static boolean skips(FocusTraversalPolicy policy) {
        return policy instanceof SkipPolicy;
    }

    /** 一层过滤：只在"下一站/上一站是静态文字"时多走几步，其余全数交给基策略。 */
    private static final class SkipPolicy extends FocusTraversalPolicy {

        private final FocusTraversalPolicy base;

        SkipPolicy(FocusTraversalPolicy base) {
            this.base = base;
        }

        /**
         * 沿环往下走，直到落点不是静态文字。
         *
         * <p>{@code stop} 是出发的那一件：整环都是静态文字时绕一圈回到出发点就停手，
         * 免得这里死循环——那种窗口本来也没有可 Tab 的东西。
         */
        private Component walk(Component c, Container container, Component stop, boolean forward) {
            int guard = 0;
            while (c instanceof StaticText && c != stop && guard++ < 512) {
                c = forward ? base.getComponentAfter(container, c)
                            : base.getComponentBefore(container, c);
            }
            return c;
        }

        @Override
        public Component getComponentAfter(Container container, Component a) {
            return walk(base.getComponentAfter(container, a), container, a, true);
        }

        @Override
        public Component getComponentBefore(Container container, Component a) {
            return walk(base.getComponentBefore(container, a), container, a, false);
        }

        @Override
        public Component getFirstComponent(Container container) {
            return walk(base.getFirstComponent(container), container, null, true);
        }

        @Override
        public Component getLastComponent(Container container) {
            return walk(base.getLastComponent(container), container, null, false);
        }

        @Override
        public Component getDefaultComponent(Container container) {
            return walk(base.getDefaultComponent(container), container, null, true);
        }
    }
}
