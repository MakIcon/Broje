package dev.broje.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.OptionWidget;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import net.vulkanmod.config.option.Option;

/** An action has no staged value: a normal full-width button in VulkanMod's list. */
public final class ActionOption extends Option<Boolean> {
    private final Runnable action;

    public ActionOption(Component name, Component tooltip, Runnable action) {
        super(name, ignored -> {}, () -> false, ignored -> Component.empty());
        this.action = action;
        setTooltip(ignored -> tooltip);
    }

    public void press() { action.run(); }
    @Override public boolean isChanged() { return false; }
    @Override public void apply() {}
    @Override protected OptionWidget<?> createWidget() { return new ActionWidget(this); }

    private static final class ActionWidget extends OptionWidget<ActionOption> {
        private final ActionOption action;
        private final VButtonWidget button;

        ActionWidget(ActionOption action) {
            super(action, action.getName());
            this.action = action;
            button = new VButtonWidget(0,0,0,0,action.getName(), ignored -> action.press());
        }
        @Override public void renderWidget(double mouseX, double mouseY) {
            button.setDimensions(x,y,width,height);
            button.setActive(active);
            button.setFocused(isFocused());
            button.render(mouseX,mouseY);
        }
        @Override protected boolean clicked(double mx, double my) { return isMouseOver(mx,my); }
        @Override protected void renderControls(double mx, double my) {}
        @Override public void onClick(double mx, double my) { action.press(); }
        @Override public void onRelease(double mx, double my) {}
        @Override protected void onDrag(double mx, double my, double dx, double dy) {}
        @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) { return false; }
        @Override public boolean keyPressed(KeyEvent event) {
            if (active && visible && isFocused() && (event.key()==257 || event.key()==335 || event.key()==32)) {
                playDownSound(Minecraft.getInstance().getSoundManager());
                action.press(); return true;
            }
            return false;
        }
    }
}
