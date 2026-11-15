package com.gtnh.sculptor.client;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.DynamicDrawable;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PageButton;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.ToggleButton;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnh.sculptor.SculptorMod;
import com.gtnh.sculptor.brush.BrushSettings;
import com.gtnh.sculptor.brush.SculptorBrush;
import com.gtnh.sculptor.camera.CameraKeyframe;
import com.gtnh.sculptor.camera.CameraPathException;
import com.gtnh.sculptor.camera.CameraPathStore;
import com.gtnh.sculptor.camera.CameraSettings;
import com.gtnh.sculptor.camera.CameraSettings.PathSpeed;
import com.gtnh.sculptor.camera.SculptorCameraController;
import com.gtnh.sculptor.layers.LayerFill;
import com.gtnh.sculptor.layers.LayerSettings;

/**
 * The editor panel, a modal ModularUI2 screen with three tabs: the brush settings, the layer rows of {@code /sclayers},
 * and the camera speeds and paths.
 *
 * <p>
 * Each control writes to {@link BrushSettings}, {@link LayerSettings} or {@link CameraSettings} at once. A change of a
 * brush setting increments the {@link BrushSettings#getRevision revision}, and {@link SculptorBrushTool} rebuilds the
 * preview on the next tick.
 * </p>
 */
public class SculptorPanel extends CustomModularScreen {

    // the tabs stand TAB_HEIGHT above the panel. At GUI scale 2 on an 854 by 480 window the screen is 240 high, and the
    // tabs and the panel together are 208 high
    private static final int PANEL_WIDTH = 200;
    private static final int PANEL_HEIGHT = 180;
    private static final int TAB_HEIGHT = 28;
    private static final int PAGE_PADDING = 6;
    private static final int ROW_GAP = 3;
    private static final int ROW_HEIGHT = 16;
    private static final int SLIDER_ROW_HEIGHT = 14;
    private static final int LABEL_WIDTH = 92;
    private static final int SLOT_SIZE = 18;
    private static final int REST_TOGGLE_WIDTH = 26;

    // the panel is a new screen on each open. The open tab, the layer rows and the result of the last path save or load
    // stay for the next open
    private static int openTab;
    private static final LayerSettings LAYERS = new LayerSettings();
    private static IKey pathStatus = IKey.EMPTY;

    public SculptorPanel() {
        super(SculptorMod.MOD_ID);
    }

    // with the panel open, onKeyPressed() sets the selected slot from the hotbar keys, so `takeHeldBlock()` can take a
    // block from any slot. A focused text field gets each key first, so a digit typed into a count field goes into the
    // field
    @Override
    public boolean onKeyPressed(char typedChar, int keyCode) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (getContext().isFocused() || mc.thePlayer == null) return super.onKeyPressed(typedChar, keyCode);

        final KeyBinding[] hotbarKeys = mc.gameSettings.keyBindsHotbar;
        for (int slot = 0; slot < hotbarKeys.length; slot++) {
            if (hotbarKeys[slot].getKeyCode() != keyCode) continue;
            mc.thePlayer.inventory.currentItem = slot;
            return true;
        }
        return super.onKeyPressed(typedChar, keyCode);
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        final ModularPanel panel = ModularPanel.defaultPanel("sculptor", PANEL_WIDTH, PANEL_HEIGHT);
        // move the panel down by half the tab height, so the tabs and the panel are centred together
        panel.topRel(0.5F, TAB_HEIGHT / 2, 0.5F);

        final PagedWidget.Controller tabs = new PagedWidget.Controller();
        final Flow tabRow = Flow.row()
            .coverChildren()
            .topRel(0.0F, 4, 1.0F);
        for (final Tab tab : Tab.values()) tabRow.child(tabButton(tab, tabs));
        panel.child(tabRow);

        panel.child(
            new PagedWidget<>().sizeRel(1.0F)
                .controller(tabs)
                .initialPage(openTab)
                .onPageChange(page -> openTab = page)
                .addPage(buildBrushPage(BrushSettings.instance()))
                .addPage(buildLayersPage())
                .addPage(buildCameraPage(CameraSettings.instance())));
        return panel;
    }

    // the first tab has the texture that joins the left edge of the panel
    private static PageButton tabButton(Tab tab, PagedWidget.Controller tabs) {
        return new PageButton(tab.ordinal(), tabs).tab(GuiTextures.TAB_TOP, (tab.ordinal() == 0) ? -1 : 0)
            .overlay(new ItemDrawable(tab.icon).asIcon())
            .addTooltipLine(IKey.lang(tab.langKey));
    }

    /**
     * The brush page: the brush, the size and strength sliders, the material, the three placement modes, and
     * auto-smooth. A row that the current brush does not use is hidden.
     */
    private static Flow buildBrushPage(BrushSettings settings) {
        final Flow page = page(Tab.BRUSH);

        page.child(
            Flow.row()
                .widthRel(1.0F)
                .height(ROW_HEIGHT)
                .child(iconButton(GuiTextures.MOVE_LEFT, "sculptor.panel.previous").onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .previous());
                    return true;
                }))
                .child(
                    new TextWidget<>(
                        IKey.lang(
                            () -> settings.getBrush()
                                .getLangKey())).textAlign(Alignment.Center)
                                    .expanded()
                                    .heightRel(1.0F))
                .child(iconButton(GuiTextures.MOVE_RIGHT, "sculptor.panel.next").onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .next());
                    return true;
                })));

        page.child(
            sliderRow(
                IKey.lang("sculptor.panel.radius", () -> new Object[] { settings.getRadius() }),
                new SliderWidget().bounds(BrushSettings.MIN_RADIUS, BrushSettings.MAX_RADIUS)
                    .stopper(1)
                    .value(
                        new DoubleValue.Dynamic(
                            settings::getRadius,
                            value -> settings.setRadius((int) Math.round(value))))));

        // one slider for each brush with a second parameter, with the range of that brush
        for (final SculptorBrush brush : SculptorBrush.values()) {
            if (!brush.hasSecondary()) continue;
            page.child(
                sliderRow(
                    IKey.lang(brush.getSecondaryLangKey(), () -> new Object[] { settings.getSecondary() }),
                    new SliderWidget().bounds(brush.getSecondaryMin(), brush.getSecondaryMax())
                        .stopper(1)
                        .value(
                            new DoubleValue.Dynamic(
                                settings::getSecondary,
                                value -> settings.setSecondary((int) Math.round(value)))))
                                    .setEnabledIf(row -> settings.getBrush() == brush));
        }

        page.child(
            sliderRow(
                IKey.lang(
                    "sculptor.panel.strength",
                    () -> new Object[] { Math.round(settings.getStrength() * 100.0D) }),
                new SliderWidget().bounds(0.05D, 1.0D)
                    .value(new DoubleValue.Dynamic(settings::getStrength, settings::setStrength))).setEnabledIf(
                        row -> settings.getBrush()
                            .usesStrength()));

        final BlockView material = new BlockView(settings::getMaterialId, settings::getMaterialData);
        page.child(
            Flow.row()
                .widthRel(1.0F)
                .height(SLOT_SIZE)
                .childPadding(ROW_GAP)
                .child(
                    material.createSlot()
                        .size(SLOT_SIZE))
                .child(
                    new TextWidget<>(material.createName()).textAlign(Alignment.CenterLeft)
                        .expanded()
                        .heightRel(1.0F))
                .child(
                    new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.use_held_block"))
                        .width(80)
                        .heightRel(1.0F)
                        .onMousePressed(button -> {
                            takeHeldBlock(settings::setMaterial);
                            return true;
                        })));

        page.child(
            cycleButton(
                () -> settings.getPlacementMask()
                    .getLangKey(),
                () -> settings.setPlacementMask(
                    settings.getPlacementMask()
                        .next())));
        page.child(
            cycleButton(
                () -> settings.isUseSelectedMaterial() ? "sculptor.panel.material.selected"
                    : "sculptor.panel.material.existing",
                () -> settings.setUseSelectedMaterial(!settings.isUseSelectedMaterial())));
        page.child(
            cycleButton(
                () -> settings.getOverhang()
                    .getLangKey(),
                () -> settings.setOverhang(
                    settings.getOverhang()
                        .next())));

        page.child(
            sliderRow(
                IKey.lang(
                    () -> (settings.getAutoSmooth() <= 0.0D) ? "sculptor.panel.auto_smooth.off"
                        : "sculptor.panel.auto_smooth",
                    () -> new Object[] { Math.round(settings.getAutoSmooth() * 100.0D) }),
                new SliderWidget().bounds(0.0D, 1.0D)
                    .value(new DoubleValue.Dynamic(settings::getAutoSmooth, settings::setAutoSmooth))).setEnabledIf(
                        row -> settings.getBrush()
                            .isAutoSmoothable()));
        return page;
    }

    /**
     * The layers page: up to {@link LayerSettings#MAX_LAYERS} rows, the spill, and a button for {@link #applyLayers}.
     * Each possible row has a row widget, and a widget past the row count is hidden.
     */
    private static Flow buildLayersPage() {
        final Flow page = page(Tab.LAYERS);

        for (int index = 0; index < LayerSettings.MAX_LAYERS; index++) page.child(buildLayerRow(index));

        page.child(
            Flow.row()
                .widthRel(1.0F)
                .height(SLIDER_ROW_HEIGHT)
                .childPadding(ROW_GAP)
                .child(iconButton(GuiTextures.ADD, "sculptor.panel.layers.add").onMousePressed(button -> {
                    LAYERS.addRow();
                    return true;
                })
                    .setEnabledIf(button -> LAYERS.getRowCount() < LayerSettings.MAX_LAYERS))
                .child(
                    new TextWidget<>(IKey.lang("sculptor.panel.layers.spill", () -> new Object[] { LAYERS.getSpill() }))
                        .textAlign(Alignment.CenterLeft)
                        .width(LABEL_WIDTH - SLIDER_ROW_HEIGHT - ROW_GAP)
                        .heightRel(1.0F))
                .child(
                    new SliderWidget().bounds(0, LayerFill.MAX_SPILL)
                        .stopper(1)
                        .value(
                            new DoubleValue.Dynamic(
                                LAYERS::getSpill,
                                value -> LAYERS.setSpill((int) Math.round(value))))
                        .expanded()
                        .heightRel(1.0F)));

        page.child(
            new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.layers.apply"))
                .widthRel(1.0F)
                .height(ROW_HEIGHT)
                .onMousePressed(button -> {
                    applyLayers();
                    return true;
                }));
        return page;
    }

    /** One layer row: the block, the name of the block, the count, the rest toggle and the remove button. */
    private static Flow buildLayerRow(int index) {
        final BlockView block = new BlockView(
            () -> readLayerRow(index).blockId(),
            () -> readLayerRow(index).blockData());
        return Flow.row()
            .widthRel(1.0F)
            .height(SLOT_SIZE)
            .childPadding(ROW_GAP)
            .setEnabledIf(row -> index < LAYERS.getRowCount())
            .child(
                new ButtonWidget<>().background(GuiTextures.SLOT_ITEM)
                    .overlay(block.createIcon())
                    .size(SLOT_SIZE)
                    .addTooltipLine(IKey.lang("sculptor.panel.use_held_block"))
                    .onMousePressed(button -> {
                        takeHeldBlock((blockId, blockData) -> LAYERS.setBlock(index, blockId, blockData));
                        return true;
                    }))
            .child(
                new TextWidget<>(block.createName()).textAlign(Alignment.CenterLeft)
                    .expanded()
                    .heightRel(1.0F))
            .child(
                new TextFieldWidget()
                    .value(
                        new IntValue.Dynamic(() -> readLayerRow(index).count(), count -> LAYERS.setCount(index, count)))
                    .numbersInt(1, LayerSettings.MAX_COUNT)
                    .size(34, ROW_HEIGHT)
                    .setEnabledIf(field -> !readLayerRow(index).rest()))
            .child(
                new ToggleButton()
                    .value(new BoolValue.Dynamic(() -> readLayerRow(index).rest(), rest -> LAYERS.toggleRest(index)))
                    .overlay(IKey.lang("sculptor.panel.layers.rest"))
                    .addTooltipLine(IKey.lang("sculptor.panel.layers.rest.tooltip"))
                    .size(REST_TOGGLE_WIDTH, ROW_HEIGHT))
            .child(iconButton(GuiTextures.CROSS_TINY, "sculptor.panel.layers.remove").onMousePressed(button -> {
                LAYERS.removeRow(index);
                return true;
            }));
    }

    // a row widget past the row count is hidden, and reads the last row so that no getter of the widget reads past the
    // end of the rows
    private static LayerSettings.LayerRow readLayerRow(int index) {
        return LAYERS.getRow(Math.min(index, LAYERS.getRowCount() - 1));
    }

    // close the panel first, so the chat line of the result is visible. addToSentMessages() puts the command in the
    // chat history, and the player can recall the command with the up arrow in chat
    private static void applyLayers() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        final String command = LAYERS.buildCommand();
        mc.displayGuiScreen(null);
        mc.ingameGUI.getChatGUI()
            .addToSentMessages(command);
        mc.thePlayer.sendChatMessage(command);
    }

    /** The camera page: the fly speed, the keyframe approach speeds, and the save and load of a path. */
    private static Flow buildCameraPage(CameraSettings settings) {
        final Flow page = page(Tab.CAMERA);

        page.child(speedRow("sculptor.panel.fly_speed", settings::getFlySpeed, settings::setFlySpeed));
        page.child(label("sculptor.panel.path_speeds"));
        for (final PathSpeed tier : PathSpeed.values()) {
            page.child(
                speedRow(
                    tier.getLangKey(),
                    () -> settings.getPathSpeed(tier),
                    value -> settings.setPathSpeed(tier, value)));
        }

        page.child(label("sculptor.panel.path_file"));
        page.child(
            new TextFieldWidget().value(new StringValue.Dynamic(settings::getPathName, settings::setPathName))
                .setMaxLength(64)
                .widthRel(1.0F)
                .height(ROW_HEIGHT));
        page.child(
            Flow.row()
                .widthRel(1.0F)
                .height(ROW_HEIGHT)
                .childPadding(ROW_GAP)
                .child(
                    new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.save"))
                        .expanded()
                        .heightRel(1.0F)
                        .onMousePressed(button -> {
                            savePath(settings.getPathName());
                            return true;
                        }))
                .child(
                    new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.load"))
                        .expanded()
                        .heightRel(1.0F)
                        .onMousePressed(button -> {
                            loadPath(settings.getPathName());
                            return true;
                        })));
        page.child(
            new TextWidget<>(IKey.dynamicKey(() -> pathStatus)).scale(0.8F)
                .textAlign(Alignment.CenterLeft)
                .widthRel(1.0F)
                .height(10));
        return page;
    }

    /** Save the camera path under the name, and set {@code pathStatus} to the result. */
    private static void savePath(String name) {
        final SculptorCameraController camera = SculptorCameraController.instance();
        if (camera == null) return;

        if (!CameraPathStore.isValidName(name)) {
            pathStatus = IKey.lang("sculptor.panel.status.bad_name");
            return;
        }

        final List<CameraKeyframe> path = camera.getPath();
        if (path.isEmpty()) {
            pathStatus = IKey.lang("sculptor.panel.status.nothing_to_save");
            return;
        }

        try {
            CameraPathStore.instance()
                .save(name, path);
            pathStatus = IKey.lang("sculptor.panel.status.saved", path.size());
        } catch (CameraPathException exception) {
            pathStatus = IKey.lang(exception.getLangKey(), exception.getFormatValues());
        }
    }

    private static void loadPath(String name) {
        final SculptorCameraController camera = SculptorCameraController.instance();
        if (camera == null) return;

        if (!CameraPathStore.isValidName(name)) {
            pathStatus = IKey.lang("sculptor.panel.status.bad_name");
            return;
        }

        try {
            final List<CameraKeyframe> path = CameraPathStore.instance()
                .load(name);
            camera.setPath(path);
            pathStatus = IKey.lang("sculptor.panel.status.loaded", path.size());
        } catch (CameraPathException exception) {
            pathStatus = IKey.lang(exception.getLangKey(), exception.getFormatValues());
        }
    }

    // a column with the heading of the tab. A hidden row takes no space
    private static Flow page(Tab tab) {
        return Flow.column()
            .sizeRel(1.0F)
            .padding(PAGE_PADDING)
            .childPadding(ROW_GAP)
            .collapseDisabledChild()
            .mainAxisAlignment(Alignment.MainAxis.START)
            .child(label(tab.headingKey));
    }

    private static TextWidget<?> label(String langKey) {
        return new TextWidget<>(IKey.lang(langKey)).textAlign(Alignment.CenterLeft)
            .widthRel(1.0F)
            .height(10);
    }

    private static Flow sliderRow(IKey label, SliderWidget slider) {
        return Flow.row()
            .widthRel(1.0F)
            .height(SLIDER_ROW_HEIGHT)
            .child(
                new TextWidget<>(label).textAlign(Alignment.CenterLeft)
                    .width(LABEL_WIDTH)
                    .heightRel(1.0F))
            .child(
                slider.expanded()
                    .heightRel(1.0F));
    }

    // a slow drift is often under one block per second, so the field takes decimals
    private static Flow speedRow(String langKey, DoubleSupplier getter, DoubleConsumer setter) {
        return Flow.row()
            .widthRel(1.0F)
            .height(ROW_HEIGHT)
            .child(
                new TextWidget<>(IKey.lang(langKey)).textAlign(Alignment.CenterLeft)
                    .width(LABEL_WIDTH)
                    .heightRel(1.0F))
            .child(
                new TextFieldWidget().value(new DoubleValue.Dynamic(getter, setter))
                    .numbersDouble(0.1D, 1000.0D)
                    .expanded()
                    .heightRel(1.0F));
    }

    private static ButtonWidget<?> cycleButton(Supplier<String> langKey, Runnable next) {
        return new ButtonWidget<>().overlay(IKey.lang(langKey))
            .widthRel(1.0F)
            .height(ROW_HEIGHT)
            .onMousePressed(button -> {
                next.run();
                return true;
            });
    }

    private static ButtonWidget<?> iconButton(IDrawable icon, String tooltipKey) {
        return new ButtonWidget<>().overlay(icon)
            .size(ROW_HEIGHT)
            .addTooltipLine(IKey.lang(tooltipKey));
    }

    // with an empty hand, the block is air. With an item that is not a block in the hand, the block stays unchanged
    private static void takeHeldBlock(BlockTarget target) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        final ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            target.setBlock(Block.getIdFromBlock(Blocks.air), 0);
            return;
        }

        final Block block = Block.getBlockFromItem(held.getItem());
        if (block == null || block == Blocks.air) return;

        target.setBlock(Block.getIdFromBlock(block), held.getItemDamage() & 0xF);
    }

    /** The three tabs, in the order of the pages, with the item icon, the tooltip and the page heading of each tab. */
    private enum Tab {

        BRUSH(Items.iron_shovel, "sculptor.panel.brush", "sculptor.panel.brush"),
        LAYERS(Item.getItemFromBlock(Blocks.sandstone), "sculptor.panel.layers", "sculptor.panel.layers"),
        CAMERA(Items.ender_eye, "sculptor.panel.camera.tab", "sculptor.panel.camera");

        private final Item icon;
        private final String langKey;
        private final String headingKey;

        Tab(Item icon, String langKey, String headingKey) {
            this.icon = icon;
            this.langKey = langKey;
            this.headingKey = headingKey;
        }
    }

    /** A setter of a block id and a block data value. */
    @FunctionalInterface
    private interface BlockTarget {

        void setBlock(int blockId, int blockData);
    }

    /**
     * The item icon and the name of the block that two getters return. {@link #refresh} builds a new item stack and a
     * new name only when the block changes. For a block with no item, such as air, the slot is empty.
     */
    private static final class BlockView {

        private final IntSupplier blockIdGetter;
        private final IntSupplier blockDataGetter;
        private final ItemDrawable icon = new ItemDrawable();
        private String name = "";
        private int blockId = -1;
        private int blockData = -1;

        private BlockView(IntSupplier blockIdGetter, IntSupplier blockDataGetter) {
            this.blockIdGetter = blockIdGetter;
            this.blockDataGetter = blockDataGetter;
        }

        private ParentWidget<?> createSlot() {
            return new ParentWidget<>().background(GuiTextures.SLOT_ITEM)
                .overlay(createIcon());
        }

        private IDrawable createIcon() {
            return new DynamicDrawable(() -> {
                refresh();
                return (icon.getItem() == null) ? IDrawable.EMPTY : icon;
            });
        }

        private IKey createName() {
            return IKey.dynamic(() -> {
                refresh();
                return name;
            });
        }

        private void refresh() {
            final int newId = blockIdGetter.getAsInt();
            final int newData = blockDataGetter.getAsInt();
            if (newId == blockId && newData == blockData) return;

            blockId = newId;
            blockData = newData;
            final Item item = Item.getItemFromBlock(Block.getBlockById(newId));
            icon.setItem((item == null) ? null : new ItemStack(item, 1, newData));
            name = readBlockName(newId, newData);
        }

        // the item name depends on the data value, such as Pink Wool for wool with data 6. For a block with no item,
        // such as a wheat crop, the name is the name of the block and the data value
        private static String readBlockName(int blockId, int blockData) {
            final Block block = Block.getBlockById(blockId);
            if (block == null) return StatCollector.translateToLocal("sculptor.panel.material.unknown");
            // vanilla air has no lang entry
            if (block == Blocks.air) return StatCollector.translateToLocal("sculptor.panel.material.air");
            final Item item = Item.getItemFromBlock(block);
            if (item != null) return new ItemStack(item, 1, blockData).getDisplayName();
            return (blockData != 0)
                ? StatCollector
                    .translateToLocalFormatted("sculptor.panel.material.with_data", block.getLocalizedName(), blockData)
                : block.getLocalizedName();
        }
    }
}
