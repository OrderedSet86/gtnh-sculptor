package com.gtnh.sculptor.client;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.DoubleValue;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
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

/**
 * The editor panel, a modal ModularUI2 screen with the brush settings on the left and the camera speeds on the right.
 *
 * <p>
 * Each control writes to {@link BrushSettings} or {@link CameraSettings} at once. A change of a brush setting
 * increments the {@link BrushSettings#getRevision revision}, and {@link SculptorBrushTool} rebuilds the preview on the
 * next tick.
 * </p>
 */
public class SculptorPanel extends CustomModularScreen {

    private static final int PANEL_WIDTH = 320;
    // the brush column is the longer column, and ends at y=286
    private static final int PANEL_HEIGHT = 292;
    private static final int ROW_HEIGHT = 18;

    private static final int LEFT_X = 8;
    private static final int RIGHT_X = 168;
    private static final int COLUMN_WIDTH = 144;

    // a slider has fixed bounds, and the range of the second parameter depends on the brush. The slider spans the
    // widest range, and BrushSettings.setSecondary() clamps the value to the range of the brush
    private static final int SECONDARY_SLIDER_MAX = 32;

    // the panel is a new screen on each open. The result of the last save or load stays for the next open
    private static IKey pathStatus = IKey.EMPTY;

    public SculptorPanel() {
        super(SculptorMod.MOD_ID);
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        ModularPanel panel = ModularPanel.defaultPanel("sculptor", PANEL_WIDTH, PANEL_HEIGHT);
        BrushSettings brush = BrushSettings.instance();
        CameraSettings camera = CameraSettings.instance();

        panel.child(
            new TextWidget<>(IKey.lang("sculptor.panel.title")).scale(1.2f)
                .textAlign(Alignment.Center)
                .left(0)
                .right(0)
                .top(6)
                .height(ROW_HEIGHT));

        buildBrushColumn(panel, brush);
        buildCameraColumn(panel, camera);

        return panel;
    }

    /** Add the brush controls to the left column, from y = 28 down. */
    private void buildBrushColumn(ModularPanel panel, BrushSettings settings) {
        int y = 28;

        panel.child(label("sculptor.panel.brush", LEFT_X, y));
        y += ROW_HEIGHT;

        panel.child(
            new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.previous"))
                .size(16, ROW_HEIGHT)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .previous());
                    return true;
                }));

        panel.child(
            new TextWidget<>(
                IKey.lang(
                    () -> settings.getBrush()
                        .getLangKey())).textAlign(Alignment.Center)
                            .left(LEFT_X + 20)
                            .width(COLUMN_WIDTH - 40)
                            .top(y)
                            .height(ROW_HEIGHT));

        panel.child(
            new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.next"))
                .size(16, ROW_HEIGHT)
                .left(LEFT_X + COLUMN_WIDTH - 16)
                .top(y)
                .onMousePressed(button -> {
                    settings.setBrush(
                        settings.getBrush()
                            .next());
                    return true;
                }));
        y += ROW_HEIGHT + 4;

        panel.child(dynamicLabel(() -> "sculptor.panel.radius", () -> new Object[] { settings.getRadius() }, y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(BrushSettings.MIN_RADIUS, BrushSettings.MAX_RADIUS)
                .stopper(1)
                .value(
                    new DoubleValue.Dynamic(
                        () -> settings.getRadius(),
                        value -> settings.setRadius((int) Math.round(value))))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
        y += 20;

        panel.child(dynamicLabel(() -> {
            final SculptorBrush brush = settings.getBrush();
            return brush.hasSecondary() ? brush.getSecondaryLangKey() : "sculptor.panel.secondary.unused";
        }, () -> new Object[] { settings.getSecondary() }, y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(1, SECONDARY_SLIDER_MAX)
                .stopper(1)
                .value(
                    new DoubleValue.Dynamic(
                        () -> settings.getSecondary(),
                        value -> settings.setSecondary((int) Math.round(value))))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
        y += 20;

        panel.child(
            dynamicLabel(
                () -> settings.getBrush()
                    .usesStrength() ? "sculptor.panel.strength" : "sculptor.panel.strength.unused",
                () -> new Object[] { Math.round(settings.getStrength() * 100.0D) },
                y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(0.05D, 1.0D)
                .value(new DoubleValue.Dynamic(settings::getStrength, settings::setStrength))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
        y += 20;

        panel
            .child(dynamicLabel(() -> "sculptor.panel.material", () -> new Object[] { readMaterialName(settings) }, y));
        y += 12;
        panel.child(
            new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.use_held_block"))
                .size(COLUMN_WIDTH, 16)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    takeHeldBlock(settings);
                    return true;
                }));
        y += 22;

        panel.child(
            new ButtonWidget<>().overlay(
                IKey.lang(
                    () -> settings.getPlacementMask()
                        .getLangKey()))
                .size(COLUMN_WIDTH, 16)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    settings.setPlacementMask(
                        settings.getPlacementMask()
                            .next());
                    return true;
                }));
        y += 22;

        panel.child(
            new ButtonWidget<>()
                .overlay(
                    IKey.lang(
                        () -> settings.isUseSelectedMaterial() ? "sculptor.panel.material.selected"
                            : "sculptor.panel.material.existing"))
                .size(COLUMN_WIDTH, 16)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    settings.setUseSelectedMaterial(!settings.isUseSelectedMaterial());
                    return true;
                }));
        y += 22;

        panel.child(
            new ButtonWidget<>().overlay(
                IKey.lang(
                    () -> settings.getOverhang()
                        .getLangKey()))
                .size(COLUMN_WIDTH, 16)
                .left(LEFT_X)
                .top(y)
                .onMousePressed(button -> {
                    settings.setOverhang(
                        settings.getOverhang()
                            .next());
                    return true;
                }));
        y += 22;

        panel.child(
            dynamicLabel(
                () -> (settings.getAutoSmooth() <= 0.0D) ? "sculptor.panel.auto_smooth.off"
                    : "sculptor.panel.auto_smooth",
                () -> new Object[] { Math.round(settings.getAutoSmooth() * 100.0D) },
                y));
        y += 12;
        panel.child(
            new SliderWidget().bounds(0.0D, 1.0D)
                .value(new DoubleValue.Dynamic(settings::getAutoSmooth, settings::setAutoSmooth))
                .size(COLUMN_WIDTH, 14)
                .left(LEFT_X)
                .top(y));
    }

    /** Add the camera speed fields and the path buttons to the right column, from y = 28 down. */
    private void buildCameraColumn(ModularPanel panel, CameraSettings settings) {
        int y = 28;

        panel.child(label("sculptor.panel.camera", RIGHT_X, y));
        y += ROW_HEIGHT + 2;

        panel.child(fieldLabel("sculptor.panel.fly_speed", y));
        panel.child(speedField(y, settings::getFlySpeed, settings::setFlySpeed));
        y += 20;

        panel.child(label("sculptor.panel.path_speeds", RIGHT_X, y));
        y += 14;

        for (final PathSpeed tier : PathSpeed.values()) {
            panel.child(fieldLabel(tier.getLangKey(), y));
            panel.child(speedField(y, () -> settings.getPathSpeed(tier), value -> settings.setPathSpeed(tier, value)));
            y += 20;
        }

        buildPathStorageRows(panel, settings, y);
    }

    /** The save and load buttons of the camera path. {@code /scpath} also lists and deletes paths. */
    private void buildPathStorageRows(ModularPanel panel, CameraSettings settings, int y) {
        panel.child(label("sculptor.panel.path_file", RIGHT_X, y));
        y += 14;

        panel.child(
            new TextFieldWidget().value(new StringValue.Dynamic(settings::getPathName, settings::setPathName))
                .setMaxLength(64)
                .size(COLUMN_WIDTH, 16)
                .left(RIGHT_X)
                .top(y));
        y += 20;

        int buttonWidth = (COLUMN_WIDTH - 4) / 2;

        panel.child(
            new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.save"))
                .size(buttonWidth, 16)
                .left(RIGHT_X)
                .top(y)
                .onMousePressed(button -> {
                    savePath(settings.getPathName());
                    return true;
                }));

        panel.child(
            new ButtonWidget<>().overlay(IKey.lang("sculptor.panel.load"))
                .size(buttonWidth, 16)
                .left(RIGHT_X + buttonWidth + 4)
                .top(y)
                .onMousePressed(button -> {
                    loadPath(settings.getPathName());
                    return true;
                }));
        y += 20;

        panel.child(
            new TextWidget<>(IKey.dynamicKey(() -> pathStatus)).scale(0.8f)
                .textAlign(Alignment.CenterLeft)
                .left(RIGHT_X)
                .width(COLUMN_WIDTH)
                .top(y)
                .height(20));
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

    private static TextWidget<?> fieldLabel(String langKey, int y) {
        return new TextWidget<>(IKey.lang(langKey)).textAlign(Alignment.CenterLeft)
            .left(RIGHT_X)
            .width(50)
            .top(y)
            .height(16);
    }

    // a slow drift is often under one block per second, so the field takes decimals
    private static TextFieldWidget speedField(int y, DoubleSupplier getter, DoubleConsumer setter) {
        return new TextFieldWidget().value(new DoubleValue.Dynamic(getter, setter))
            .numbersDouble(0.1D, 1000.0D)
            .size(COLUMN_WIDTH - 54, 16)
            .left(RIGHT_X + 54)
            .top(y);
    }

    private static TextWidget<?> label(String langKey, int x, int y) {
        return new TextWidget<>(IKey.lang(langKey)).textAlign(Alignment.CenterLeft)
            .left(x)
            .width(COLUMN_WIDTH)
            .top(y)
            .height(14);
    }

    private static TextWidget<?> dynamicLabel(Supplier<String> langKey, Supplier<Object[]> formatValues, int y) {
        return new TextWidget<>(IKey.lang(langKey, formatValues)).textAlign(Alignment.CenterLeft)
            .left(LEFT_X)
            .width(COLUMN_WIDTH)
            .top(y)
            .height(12);
    }

    private static void takeHeldBlock(BrushSettings settings) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) return;

        Block block = Block.getBlockFromItem(held.getItem());
        if (block == null || block == Blocks.air) return;

        settings.setMaterial(Block.getIdFromBlock(block), held.getItemDamage() & 0xF);
    }

    private static String readMaterialName(BrushSettings settings) {
        final Block block = Block.getBlockById(settings.getMaterialId());
        if (block == null) return StatCollector.translateToLocal("sculptor.panel.material.unknown");
        return (settings.getMaterialData() != 0) ? StatCollector.translateToLocalFormatted(
            "sculptor.panel.material.with_data",
            block.getLocalizedName(),
            settings.getMaterialData()) : block.getLocalizedName();
    }

}
