package art.arcane.volmlib.nativelib.minecraft26_2.client;

import art.arcane.volmlib.nativelib.client.ClientScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class NativeClientScreen extends Screen {
    private final ClientScreen content;
    private final NativeClientGraphics graphics = new NativeClientGraphics();

    NativeClientScreen(ClientScreen content) {
        super(Component.literal(content.title()));
        this.content = content;
    }

    protected void init() { content.init(width, height); }
    public boolean isPauseScreen() { return false; }
    public void render(GuiGraphics frame, int mouseX, int mouseY, float partialTick) {
        GuiGraphics previous = graphics.bind(frame);
        try {
            content.render(graphics, mouseX, mouseY, partialTick);
        } finally {
            graphics.bind(previous);
        }
    }
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return content.mouseDragged(dragX, dragY);
    }
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return content.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    public void removed() {
        content.removed();
        super.removed();
    }
}
