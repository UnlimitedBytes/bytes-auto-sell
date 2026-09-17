package ovh.unlimitedbytes.autosell.ui;

import ovh.unlimitedbytes.autosell.config.AutoSellConfig;
import ovh.unlimitedbytes.autosell.util.FuzzyMatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-screen allowlist editor: every registered item is listed with its icon,
 * a fuzzy search filters it, and clicking a row toggles whether the item may be
 * sold. Changes live in a working copy; Done sorts and applies them to the
 * config (and saves), Cancel/Esc discards them.
 *
 * Visual feedback: selected rows carry a green box and a subtle green tint,
 * unselected ones a hollow box and dimmed text, the hovered row is highlighted,
 * a live counter shows the selection size, and hovering a row shows its full
 * item id as a tooltip.
 */
public final class AllowlistScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int PANEL_WIDTH = 364;
	private static final int COLOR_BORDER = 0xFF3C3C52;
	private static final int COLOR_PANEL = 0xE0101018;
	private static final int COLOR_ROW_SELECTED = 0x5020A040;
	private static final int COLOR_ROW_HOVER = 0x30FFFFFF;
	private static final int COLOR_BOX_ON = 0xFF2FBF4F;
	private static final int COLOR_BOX_OFF = 0xFF606068;
	private static final int COLOR_NAME_ON = 0xFFFFFFFF;
	private static final int COLOR_NAME_OFF = 0xFF9A9AA0;
	private static final int COLOR_ID = 0xFF707078;

	private record ItemEntry(ItemStack stack, String id, String path, String name) {
	}

	private final Screen parent;
	private final Set<String> selected = new LinkedHashSet<>();
	private final List<ItemEntry> allItems = new ArrayList<>();
	private final List<ItemEntry> visible = new ArrayList<>();
	private final List<Integer> visibleScores = new ArrayList<>();

	private TextFieldWidget searchBox;
	private ButtonWidget allButton;
	private ButtonWidget noneButton;
	private ButtonWidget doneButton;
	private String query = "";
	private double scrollRows;
	private boolean draggingScrollbar;
	private int lastPanelTop;
	private int lastPanelBottom;
	private int lastPanelLeft;
	private int lastPanelRight;

	private AllowlistScreen(Screen parent) {
		super(Text.translatable("bytesautosell.allowlist.title"));
		this.parent = parent;
		selected.addAll(AutoSellConfig.get().getAllowList());
		Identifier defaultId = Registries.ITEM.getDefaultId();
		for (Item item : Registries.ITEM) {
			Identifier id = Registries.ITEM.getId(item);
			if (id.equals(defaultId)) {
				continue; // air is noise, never sellable
			}
			allItems.add(new ItemEntry(new ItemStack(item), id.toString(), id.getPath(),
					item.getName().getString()));
		}
		allItems.sort(Comparator.comparing(ItemEntry::path));
		recomputeVisible();
	}

	public static Screen create(Screen parent) {
		return new AllowlistScreen(parent);
	}

	@Override
	protected void init() {
		int left = width / 2 - PANEL_WIDTH / 2;
		searchBox = new TextFieldWidget(textRenderer, left + 4, 30, PANEL_WIDTH - 130, 16,
				Text.translatable("bytesautosell.allowlist.title"));
		searchBox.setChangedListener(value -> {
			query = value;
			scrollRows = 0;
			recomputeVisible();
		});
		addSelectableChild(searchBox);
		setFocused(searchBox);

		allButton = addDrawableChild(ButtonWidget.builder(Text.translatable("bytesautosell.allowlist.all"),
				button -> {
					for (ItemEntry entry : visible) {
						selected.add(entry.id());
					}
				}).dimensions(left + PANEL_WIDTH - 122, 28, 58, 20).build());
		noneButton = addDrawableChild(ButtonWidget.builder(Text.translatable("bytesautosell.allowlist.none"),
				button -> {
					for (ItemEntry entry : visible) {
						selected.remove(entry.id());
					}
				}).dimensions(left + PANEL_WIDTH - 60, 28, 56, 20).build());

		int bottomCenter = width / 2;
		addDrawableChild(ButtonWidget.builder(Text.translatable("bytesautosell.allowlist.cancel"),
				button -> close()).dimensions(bottomCenter - 104, height - 28, 100, 20).build());
		doneButton = addDrawableChild(ButtonWidget.builder(Text.translatable("bytesautosell.allowlist.done"),
				button -> saveAndClose()).dimensions(bottomCenter + 4, height - 28, 100, 20).build());

		lastPanelLeft = left;
		lastPanelRight = left + PANEL_WIDTH;
		lastPanelTop = 52;
		lastPanelBottom = height - 34;
	}

	private void recomputeVisible() {
		visible.clear();
		visibleScores.clear();
		for (ItemEntry entry : allItems) {
			int score = Math.max(
					FuzzyMatcher.scoreTokens(query, entry.path()),
					FuzzyMatcher.scoreTokens(query, entry.name()));
			if (score < 0) {
				score = FuzzyMatcher.scoreTokens(query, entry.id()) / 2;
			}
			if (score >= 0) {
				visible.add(entry);
				visibleScores.add(score);
			}
		}
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < visible.size(); i++) {
			order.add(i);
		}
		order.sort(Comparator.<Integer>comparingInt(i -> -visibleScores.get(i))
				.thenComparing(i -> visible.get(i).path()));
		List<ItemEntry> sorted = new ArrayList<>(visible.size());
		for (int index : order) {
			sorted.add(visible.get(index));
		}
		visible.clear();
		visible.addAll(sorted);
	}

	private void saveAndClose() {
		config().setAllowList(selected.stream().sorted().toList());
		config().save();
		close();
	}

	@Override
	public void close() {
		// Cancel: discard the working copy and return to the settings screen.
		client.setScreen(parent);
	}

	private AutoSellConfig config() {
		return AutoSellConfig.get();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		int left = width / 2 - PANEL_WIDTH / 2;
		int right = left + PANEL_WIDTH;
		int top = 52;
		int bottom = height - 34;
		lastPanelTop = top;
		lastPanelBottom = bottom;
		lastPanelLeft = left;
		lastPanelRight = right;

		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, COLOR_NAME_ON);

		context.fill(left - 1, top - 1, right + 8, bottom + 1, COLOR_BORDER);
		context.fill(left, top, right + 7, bottom, COLOR_PANEL);

		int visibleRows = Math.max(1, (bottom - top) / ROW_HEIGHT);
		int maxScrollRows = Math.max(0, visible.size() - visibleRows);
		scrollRows = Math.clamp(scrollRows, 0, maxScrollRows);
		int scrollPixels = (int) (scrollRows * ROW_HEIGHT);
		int firstRow = scrollPixels / ROW_HEIGHT;
		int firstRowOffset = scrollPixels % ROW_HEIGHT;

		context.enableScissor(left, top, right + 7, bottom);
		int rowY = top - firstRowOffset;
		for (int i = firstRow; i < visible.size() && rowY + ROW_HEIGHT > top; i++, rowY += ROW_HEIGHT) {
			if (rowY + ROW_HEIGHT < top) {
				continue;
			}
			ItemEntry entry = visible.get(i);
			boolean isSelected = selected.contains(entry.id());
			boolean hovered = mouseX >= left && mouseX < right && mouseY >= Math.max(top, rowY)
					&& mouseY < Math.min(bottom, rowY + ROW_HEIGHT);
			if (isSelected) {
				context.fill(left + 2, rowY, right + 6, rowY + ROW_HEIGHT, COLOR_ROW_SELECTED);
			} else if (hovered) {
				context.fill(left + 2, rowY, right + 6, rowY + ROW_HEIGHT, COLOR_ROW_HOVER);
			}
			int boxX = left + 8;
			int boxY = rowY + (ROW_HEIGHT - 10) / 2;
			if (isSelected) {
				context.fill(boxX, boxY, boxX + 10, boxY + 10, COLOR_BOX_ON);
				// crisp check mark: two dark bars
				context.fill(boxX + 2, boxY + 5, boxX + 4, boxY + 8, 0xFF0E2A14);
				context.fill(boxX + 4, boxY + 4, boxX + 8, boxY + 7, 0xFF0E2A14);
			} else {
				context.fill(boxX, boxY, boxX + 10, boxY + 1, COLOR_BOX_OFF);
				context.fill(boxX, boxY + 9, boxX + 10, boxY + 10, COLOR_BOX_OFF);
				context.fill(boxX, boxY, boxX + 1, boxY + 10, COLOR_BOX_OFF);
				context.fill(boxX + 9, boxY, boxX + 10, boxY + 10, COLOR_BOX_OFF);
			}
			context.drawItem(entry.stack(), left + 24, rowY + (ROW_HEIGHT - 16) / 2);
			context.drawText(textRenderer, entry.name(), left + 44, rowY + 3,
					isSelected ? COLOR_NAME_ON : COLOR_NAME_OFF, true);
			context.drawText(textRenderer, entry.id(), left + 44, rowY + 13, COLOR_ID, false);
		}
		if (visible.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("bytesautosell.allowlist.empty"),
					(left + right) / 2, (top + bottom) / 2 - 4, COLOR_ID);
		}
		context.disableScissor();

		if (maxScrollRows > 0) {
			int trackX = right + 1;
			float ratio = (float) visibleRows / visible.size();
			int thumbHeight = Math.max(12, (int) ((bottom - top) * ratio));
			int thumbY = top + (int) (((bottom - top) - thumbHeight) * (scrollRows / (double) maxScrollRows));
			context.fill(trackX, top, trackX + 5, bottom, 0xFF26262E);
			context.fill(trackX + 1, thumbY, trackX + 4, thumbY + thumbHeight,
					draggingScrollbar ? 0xFF8A8A96 : 0xFF5A5A66);
		}

		String counter = Text.translatable("bytesautosell.allowlist.selected", selected.size()).getString();
		context.drawText(textRenderer, counter, right - textRenderer.getWidth(counter) - 4, 34, COLOR_NAME_ON, true);
		context.drawText(textRenderer,
				Text.translatable("bytesautosell.allowlist.shown", visible.size(), allItems.size()).getString(),
				left + 4, height - 44, COLOR_ID, false);

		if (query.isEmpty() && getFocused() != searchBox) {
			context.drawText(textRenderer, Text.translatable("bytesautosell.allowlist.search").getString(),
					searchBox.getX() + 4, searchBox.getY() + 4, 0xFF606068, false);
		}

		ItemEntry hovered = entryAt(mouseX, mouseY);
		if (hovered != null && !draggingScrollbar) {
			Text status = selected.contains(hovered.id())
					? Text.translatable("bytesautosell.allowlist.tooltip.on").formatted(Formatting.GREEN)
					: Text.translatable("bytesautosell.allowlist.tooltip.off").formatted(Formatting.RED);
			context.drawTooltip(Text.literal(hovered.id()).append(Text.literal(" - ")).append(status),
					mouseX, mouseY);
		}
	}

	private ItemEntry entryAt(int mouseX, int mouseY) {
		if (mouseX < lastPanelLeft || mouseX >= lastPanelRight || mouseY < lastPanelTop
				|| mouseY >= lastPanelBottom) {
			return null;
		}
		int relative = mouseY - lastPanelTop + (int) (scrollRows * ROW_HEIGHT);
		int index = relative / ROW_HEIGHT;
		if (index < 0 || index >= visible.size()) {
			return null;
		}
		return visible.get(index);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) {
			return true;
		}
		int mouseX = (int) click.x();
		int mouseY = (int) click.y();
		if (click.button() == 0 && mouseX >= lastPanelRight && mouseX <= lastPanelRight + 7
				&& mouseY >= lastPanelTop && mouseY < lastPanelBottom && maxScrollRows() > 0) {
			draggingScrollbar = true;
			scrollTo(mouseY);
			return true;
		}
		ItemEntry entry = entryAt(mouseX, mouseY);
		if (entry != null && click.button() == 0) {
			if (!selected.remove(entry.id())) {
				selected.add(entry.id());
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(Click click) {
		draggingScrollbar = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (draggingScrollbar) {
			scrollTo(click.y());
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
			return true;
		}
		scrollRows -= verticalAmount * 2;
		scrollRows = Math.clamp(scrollRows, 0, maxScrollRows());
		return true;
	}

	private int maxScrollRows() {
		int visibleRows = Math.max(1, (lastPanelBottom - lastPanelTop) / ROW_HEIGHT);
		return Math.max(0, visible.size() - visibleRows);
	}

	private void scrollTo(double mouseY) {
		int visibleRows = Math.max(1, (lastPanelBottom - lastPanelTop) / ROW_HEIGHT);
		double fraction = (mouseY - lastPanelTop) / (lastPanelBottom - lastPanelTop);
		scrollRows = Math.clamp(Math.round(fraction * maxScrollRows()), 0, maxScrollRows());
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
