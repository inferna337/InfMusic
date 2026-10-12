package ru.inferna.music.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;
import ru.inferna.music.InfernaMusicClient;
import ru.inferna.music.api.JamendoTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MusicScreen extends Screen {
    private static DrawContext activeContext;
    // palette
    private static final int PANEL     = 0xE8120F20;
    private static final int STROKE    = 0x24FFFFFF;
    private static final int CARD      = 0x14FFFFFF;
    private static final int HOVER     = 0x18FFFFFF;
    private static final int FIELD     = 0x1FFFFFFF;
    private static final int FOOTER    = 0x66000000;
    private static final int TRACK_BG  = 0x26FFFFFF;
    private static final int ACCENT_A  = 0xFF8B5CF6;
    private static final int ACCENT_B  = 0xFFEC4899;
    private static final int TEXT      = 0xFFF4F1FF;
    private static final int MUTED     = 0xFF9D98B8;
    private static final int GREEN     = 0xFF4ADE80;

    private float scale = 1f;
    private int rowHeight = 34, rowPitch = 38, volumeWidth = 90;

    private final Screen parent;
    private final List<JamendoTrack> tracks = new ArrayList<>();
    private String search = "";
    private String status = "Ready";
    private boolean searching;
    private boolean draggingVolume;
    private boolean draggingTimeline;
    private float timelinePreview;
    private boolean likedMode;
    private float scroll;
    private long lastSearchAt;

    public MusicScreen(Screen parent) {
        super(Text.literal("INFMUSIC"));
        this.parent = parent;
    }

    @Override
    protected void init() {
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------- layout (единый источник координат для рендера и кликов) ----------

    private record L(int px, int py, int pw, int ph, int sideW, int footY, int unit,
                     int mainX, int mainW, int searchY, int heroY, int listY, int listBottom,
                     int cx, int volX, int volY) {
        int u(int v) { return Math.max(1, Math.round(v * unit / 100f)); }
        // Центрируем контролы: Previous(22) + gap(5) + Play(26) + gap(5) + Next(22) + gap(8) + Stop(18) = 106px total
        // Центр группы: cx - 106/2 = cx - 53
        int previousX() { return cx - 53; }
        int playX() { return cx - 53 + 22 + 5; }  int playY() { return footY + 6; }
        int nextX() { return cx - 53 + 22 + 5 + 26 + 5; }
        int stopX() { return cx - 53 + 22 + 5 + 26 + 5 + 22 + 8; }  int stopY() { return footY + 10; }
        int barX()  { return cx - 110; } int barY()  { return footY + 40; }
        int barW() { return 220; }
        int searchBtnX() { return mainX + mainW - 56; }
        int searchBtnY() { return searchY + 4; }
    }

    private L layout() {
        // Design grid from the reference: 1280x720. Screen coordinates are GUI
        // coordinates; Minecraft scales this complete surface to the window.
        float s = 1f;
        int unit = 100;
        this.scale = s;
        this.rowHeight = 34;
        this.rowPitch = 38;
        this.volumeWidth = 90;
        int pw = Math.min(980, width - 24);
        int ph = Math.min(520, height - 24);
        int px = Math.round((width - pw) / 2f), py = Math.round((height - ph) / 2f);
        int sideW = 160, footH = 62;
        int footY = py + ph - footH;
        int mainX = px + sideW + 16, mainW = pw - sideW - 32;
        int searchY = py + 16;
        int heroY = searchY + 36;
        int listY = heroY + 92;
        int volX = px + pw - 8 - 16 - volumeWidth;
        return new L(px, py, pw, ph, sideW, footY, unit, mainX, mainW, searchY, heroY, listY,
                footY - 8, px + pw / 2, volX, footY + 25);
    }

    // ---------- render ----------

    private void drawText(String value, float x, float y, int color) {
        drawText(value, x, y, color, true);
    }

    private void drawText(String value, float x, float y, int color, boolean shadow) {
        if (activeContext == null) return;
        Text text = Text.literal(value);
        if (shadow) activeContext.drawTextWithShadow(textRenderer, text, (int)x, (int)y, color);
        else activeContext.drawText(textRenderer, text, (int)x, (int)y, color, false);
    }

    /**
     * Базовый Screen.renderWithTooltip вызывает этот метод сам, ровно один раз за кадр.
     * Поэтому в render() фон рисовать не нужно, иначе будет "Can only blur once per frame".
     */
    @Override
    public void renderBackground(DrawContext g, int mouseX, int mouseY, float delta) {
        activeContext = g;
        glGradient(0, 0, width, height, 0xFF252A5A, 0xFF09120F);
    }

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float delta) {
        activeContext = g;
        L l = layout();

        int r = 8;
        glBegin();
        glRRect(l.px, l.py, l.pw, l.ph, r, PANEL);
        rrectStroke(g, l.px, l.py, l.pw, l.ph, r, STROKE);
        glEnd();

        drawSidebar(g, l);
        drawSearch(g, l, mouseX, mouseY);
        drawHero(g, l);
        drawList(g, l, mouseX, mouseY);
        drawFooter(g, l, mouseX, mouseY);
    }

    private void drawSidebar(DrawContext g, L l) {
        int x = l.px + 16, y = l.py + 18;
        drawNeonText(g, "INFMUSIC", x, y);

        int discoverY = y + 40;
        int likedY = discoverY + 32;
        drawNavItem(g, x, discoverY, "Discover", !likedMode);
        drawNavItem(g, x, likedY, "Liked tracks", likedMode);
        drawText(Integer.toString(InfernaMusicClient.CONFIG.likedTracks.size()), x + l.sideW - 42, likedY + 9, MUTED, true);
    }

    private void drawNeonText(DrawContext g, String value, int x, int y) {
        drawText(value, x - 1, y, 0x3899FFFF, false);
        drawText(value, x + 1, y, 0x3899FFFF, false);
        drawText(value, x, y, TEXT, false);
    }

    private void drawNavItem(DrawContext g, int x, int y, String label, boolean selected) {
        L l = layout(); int w = 140, h = 26, u = 1;
        if (selected) {
            glBegin(); glRRect(x - 6, y, w, h, 6, 0x2E4A2B80); glRRect(x, y + 6, 3, 14, 1, ACCENT_A); glEnd();
        }
        drawText(label, x + 12, y + 9, selected ? TEXT : MUTED);
    }

    private void drawSearch(DrawContext g, L l, int mx, int my) {
        int h = 24, btnW = 52, btnH = 16;
        glBegin(); glRRect(l.mainX, l.searchY, l.mainW, h, l.u(18), 0xB5222239); glEnd();
        boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
        String shown = search.isEmpty() ? "Search track, artist, album or name" : search + (caret ? "|" : "");
        drawText(shown, l.mainX + 14, l.searchY + 8, search.isEmpty() ? MUTED : TEXT);

        boolean hover = inside(mx, my, l.searchBtnX(), l.searchBtnY(), btnW, btnH);
        int searchBtnColor = hover ? 0xFF3A3A3A : 0xFF262626;  // Серый полупрозрачный, светлее при hover
        glBegin(); glRRect(l.searchBtnX(), l.searchBtnY(), btnW, btnH, l.u(12), searchBtnColor); glEnd();
        drawText("SEARCH", l.searchBtnX() + (btnW - textRenderer.getWidth("SEARCH")) / 2, l.searchBtnY() + 4, TEXT);
    }

    private void drawHero(DrawContext g, L l) {
        int x = l.mainX, y = l.heroY, w = l.mainW;
        glBegin(); glRRect(x, y, w, 64, 8, CARD); glEnd();
        int cover = 48;

        JamendoTrack t = InfernaMusicClient.PLAYER.currentTrack();
        boolean playing = InfernaMusicClient.PLAYER.isPlaying();
        if (t == null) {
            glBegin(); glRRect(x + 8, y + 8, cover, cover, 8, 0x26FFFFFF); glEnd();
            drawText("Nothing playing", x + 68, y + 28, MUTED);
        } else {
            drawCover(g, x + 8, y + 8, cover, t);
            drawText("NOW PLAYING", x + 68, y + 10, ACCENT_A);
            drawText(trim(t.name(), 42), x + 68, y + 24, TEXT);
            drawText(trim(t.artist(), 42), x + 68, y + 38, MUTED);
        }

        // эквалайзер; пока псевдо-анимация. Реальные уровни можно подать из PCM через FFT
        int bars = 14, gap = 5, bw = 3, bx = x + w - 14 - bars * gap, base = y + 54;
        double time = System.currentTimeMillis() / 1000.0;
        for (int i = 0; i < bars; i++) {
            double amp = playing ? 0.2 + 0.8 * Math.abs(Math.sin(time * 3 + i * 0.9) * Math.cos(time * 1.7 + i * 0.4)) : 0.1;
            int bh = Math.max(2, (int) (36 * amp));
            int c = lerp(ACCENT_A, ACCENT_B, i / (float) (bars - 1));
            glBegin(); glRRect(bx + i * gap, base - bh, bw, bh, 1, c); glEnd();
        }
    }

    private void drawList(DrawContext g, L l, int mx, int my) {
        int hy = l.heroY + 64 + 12;
        List<JamendoTrack> visibleTracks = visibleTracks();
        drawText(likedMode ? "Liked tracks" : (search.isEmpty() ? "Discover music" : "Search results"), l.mainX + 4, hy, TEXT);
        String st = trim(likedMode ? visibleTracks.size() + " liked" : status, 60);
        drawText(st, l.mainX + l.mainW - textRenderer.getWidth(st) - 4, hy, MUTED);

        int x = l.mainX, y = l.listY, w = l.mainW;
        if (!likedMode && searching) {
            rrect(g, x, y, w, 36, 8, CARD);
            float p = (System.currentTimeMillis() % 1200L) / 1200f;
            gradRRect(g, (int) (x + 14 + p * Math.max(1, w - 80)), y + 8, 40, 4, 2, ACCENT_A, ACCENT_B);
            drawText("Searching YouTube and open catalogs...", x + 14, y + 20, MUTED);
            return;
        }
        if (visibleTracks.isEmpty()) {
            rrect(g, x, y, w, 56, 8, CARD);
            drawText(likedMode ? "No liked tracks yet" : "Nothing here yet", x + 14, y + 12, TEXT);
            drawText(
                    likedMode ? "Like tracks in Discover to keep them here" : "Search a track, artist, album or name",
                    x + 14, y + 30, MUTED);
            return;
        }

        int maxScroll = Math.max(0, visibleTracks.size() * rowPitch - (l.listBottom - y));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        g.enableScissor(x, y, x + w, l.listBottom);
        for (int i = 0; i < visibleTracks.size(); i++) {
            int ry = y + i * rowPitch - (int) scroll;
            if (ry < y - rowHeight || ry > l.listBottom) continue;
            JamendoTrack t = visibleTracks.get(i);
            boolean hover = inside(mx, my, x, ry, w, rowHeight) && my >= y && my <= l.listBottom;
            if (hover) rrect(g, x, ry, w, rowHeight, l.u(12), HOVER);

            int coverSize = 24;
            drawCover(g, x + 6, ry + 5, coverSize, t);
            int textX = x + 38;
            drawText(trim(t.name(), 40), textX, ry + 7, TEXT);
            drawText(trim(t.subtitle(), 50), textX, ry + 19, MUTED);

            String time = formatSeconds(t.duration());
            drawText(time, x + w - 75 - textRenderer.getWidth(time), ry + 13, MUTED);

            boolean playable = t.isPlayable();
            int button = 20, bx = x + w - 26, by = ry + 7;
            rrect(g, bx, by, button, button, 10, playable ? (hover ? ACCENT_A : TRACK_BG) : 0x14FFFFFF);
            if (playable) {
                boolean current = sameTrack(t, InfernaMusicClient.PLAYER.currentTrack());
                String icon = current && InfernaMusicClient.PLAYER.isPlaying() ? "⏸" : "▶";
                int iconW = textRenderer.getWidth(icon);
                int iconX = bx + (button - iconW) / 2;
                int iconY = by + (button - 9) / 2;
                drawText(icon, iconX, iconY, TEXT, false);
            }
            else {
                int iconW = textRenderer.getWidth("×");
                int iconX = bx + (button - iconW) / 2;
                drawText("×", iconX, by + 6, MUTED);
            }

            int likeX = x + w - 49, likeY = ry + 8;
            boolean liked = isLiked(t);
            boolean likeHover = inside(mx, my, likeX, likeY, 16, 18) && my >= y && my <= l.listBottom;
            if (likeHover) rrect(g, likeX - 1, likeY - 1, 18, 20, 6, HOVER);
            String likeIcon = liked ? "\u2665" : "\u2661";
            int likeIconW = textRenderer.getWidth(likeIcon);
            int likeIconX = likeX + (16 - likeIconW) / 2;
            drawText(likeIcon, likeIconX, likeY + 3, liked ? 0xFFFF6B9D : playable ? MUTED : 0x66777777);
        }
        g.disableScissor();
    }

    private void drawDiscoverCards(DrawContext g, L l, int mx, int my) {
        int titleY = l.heroY + l.u(184);
        drawText("Recommended for you", l.mainX + 4, titleY, TEXT);
        String[] names = {"Rammstein - Sonne", "Rammstein - Du Hast", "Linkin Park - Numb", "Imagine Dragons - Believer"};
        String[] artists = {"Rammstein Official", "Rammstein Official", "Linkin Park", "Imagine Dragons"};
        int gap = l.u(16), cardW = Math.max(l.u(120), (l.mainW - gap * 3) / 4), imageH = l.u(112);
        int y = titleY + l.u(32);
        int[] colors = {0xFFE0559C, 0xFF486FF0, 0xFF16B6A4, 0xFFFF8C16};
        for (int i = 0; i < names.length; i++) {
            int x = l.mainX + i * (cardW + gap);
            if (x + cardW > l.mainX + l.mainW) break;
            boolean hover = inside(mx, my, x, y, cardW, imageH);
            glBegin(); glGradientRRect(x, y, cardW, imageH, 16, colors[i], lerp(colors[i], 0xFFFFFFFF, .16f)); glEnd();
            if (hover) {
                glBegin(); glRRect(x + cardW - l.u(52), y + imageH - l.u(52), l.u(40), l.u(40), l.u(20), 0xFFFFFFFF); glEnd();
                iconPlay(g, x + cardW - l.u(32), y + imageH - l.u(32), l.u(14), 0xFF181522);
            }
            drawText(trimToWidth(names[i], cardW), x, y + imageH + l.u(14), TEXT);
            drawText(trimToWidth(artists[i] + " · YouTube", cardW), x, y + imageH + l.u(34), MUTED);
        }
    }

    private void drawFooter(DrawContext g, L l, int mx, int my) {
        int footerInset = 8, footerH = 54;
        rrect(g, l.px + footerInset, l.footY, l.pw - footerInset * 2, footerH, 8, FOOTER);

        JamendoTrack t = InfernaMusicClient.PLAYER.currentTrack();
        boolean playing = InfernaMusicClient.PLAYER.isPlaying();
        int ix = l.px + footerInset + 12, coverSize = 38;
        if (t != null) {
            drawCover(g, ix, l.footY + 8, coverSize, t);
            drawText(trim(t.name(), 26), ix + 46, l.footY + 14, TEXT);
            drawText(trim(t.artist(), 26), ix + 46, l.footY + 28, MUTED);
        } else {
            drawText("Nothing playing", ix, l.footY + 22, MUTED);
        }

        // previous button
        int navButtonSize = 22;
        boolean hPrev = inside(mx, my, l.previousX(), l.footY + 10, navButtonSize, navButtonSize);
        rrect(g, l.previousX(), l.footY + 10, navButtonSize, navButtonSize, navButtonSize / 2, hPrev ? 0x40FFFFFF : 0x26FFFFFF);
        iconPrevious(g, l.previousX() + navButtonSize / 2, l.footY + 10 + navButtonSize / 2, 7, TEXT);

        // play/pause
        int playSize = 26, stopSize = 18;
        boolean hp = inside(mx, my, l.playX(), l.playY(), playSize, playSize);
        rrect(g, l.playX(), l.playY(), playSize, playSize, playSize / 2, hp ? 0xFFFFFFFF : 0xFFE9E4FF);
        int pcx = l.playX() + playSize / 2, pcy = l.playY() + playSize / 2;
        if (playing) {
            // pause icon - two vertical bars
            glBegin();
            glQuad(pcx - 4, pcy - 5, 2, 10, 0xFF120F20);
            glQuad(pcx + 2, pcy - 5, 2, 10, 0xFF120F20);
            glEnd();
        } else {
            iconPlay(g, pcx, pcy, 9, 0xFF120F20);
        }

        // next button
        boolean hNext = inside(mx, my, l.nextX(), l.footY + 10, navButtonSize, navButtonSize);
        rrect(g, l.nextX(), l.footY + 10, navButtonSize, navButtonSize, navButtonSize / 2, hNext ? 0x40FFFFFF : 0x26FFFFFF);
        iconNext(g, l.nextX() + navButtonSize / 2, l.footY + 10 + navButtonSize / 2, 7, TEXT);

        // stop
        boolean hs = inside(mx, my, l.stopX(), l.stopY(), stopSize, stopSize);
        rrect(g, l.stopX(), l.stopY(), stopSize, stopSize, stopSize / 2, hs ? 0x40FFFFFF : TRACK_BG);
        glBegin(); glQuad(l.stopX() + 6, l.stopY() + 6, 6, 6, TEXT); glEnd();

        // прогресс
        float progress = 0;
        int elapsed = 0, total = 0;
        if (t != null) {
            elapsed = InfernaMusicClient.PLAYER.elapsedSeconds();
            total = t.duration();
            int decodedDuration = InfernaMusicClient.PLAYER.decodedDurationSeconds();
            if (decodedDuration > 0) total = decodedDuration;
            progress = total <= 0 ? 0 : Math.min(1f, elapsed / (float) total);
        }
        rrect(g, l.barX(), l.barY(), 220, 4, 2, 0x26FFFFFF);
        float shownProgress = draggingTimeline ? timelinePreview : progress;
        if (shownProgress > 0) gradRRect(g, l.barX(), l.barY(), Math.max(4, (int) (220 * shownProgress)), 4, 2, ACCENT_A, ACCENT_B);
        rrect(g, l.barX() + Math.round(220 * shownProgress) - 4, l.barY() - 4, 9, 12, 4, 0xFFFFFFFF);
        String ts = formatSeconds(elapsed), te = formatSeconds(total);
        drawText(ts, l.barX() - 6 - textRenderer.getWidth(ts), l.barY() - 2, MUTED);
        drawText(te, l.barX() + 226, l.barY() - 2, MUTED);

        // громкость
        float vol = InfernaMusicClient.PLAYER.volume();
        g.getMatrices().pushMatrix();
        g.getMatrices().translate(l.volX - 21, l.volY - 3);
        g.getMatrices().scale(1.35f, 1.35f);
        drawText("\uD83D\uDD09", 0, 0, MUTED);
        g.getMatrices().popMatrix();
        rrect(g, l.volX, l.volY + 2, volumeWidth, 4, 2, 0x26FFFFFF);
        if (vol > 0) rrect(g, l.volX, l.volY + 2, Math.max(4, (int) (volumeWidth * vol)), 4, 2, 0xFFC4B5FD);
        rrect(g, l.volX + Math.round(volumeWidth * vol) - 3, l.volY - 1, 6, 10, 3, 0xFFFFFFFF);
    }

    private void drawCover(DrawContext g, int x, int y, int size, JamendoTrack t) {
        int h = Math.floorMod(String.valueOf(t.name()).hashCode(), 360);
        int a = 0xFF000000 | MathHelper.hsvToRgb(h / 360f, 0.65f, 0.85f);
        int b = 0xFF000000 | MathHelper.hsvToRgb(((h + 60) % 360) / 360f, 0.7f, 0.7f);
        gradRRect(g, x, y, size, size, Math.max(3, size / 6), a, b);
    }

    // ---------- примитивы ----------

    private static void glBegin() {
    }

    private static void glEnd() {
    }

    private static void glColor(int color) {
    }

    private static void glQuad(float x, float y, float w, float h, int color) {
        if (activeContext != null) activeContext.fill((int)x, (int)y, (int)(x + w), (int)(y + h), color);
    }

    private static void glGradient(float x, float y, float w, float h, int top, int bottom) {
        if (activeContext != null) activeContext.fillGradient((int)x, (int)y, (int)(x + w), (int)(y + h), top, bottom);
    }

    private static void glRRect(float x, float y, float w, float h, float radius, int color) {
        int ix = (int)x, iy = (int)y, iw = (int)w, ih = (int)h;
        if (iw <= 0 || ih <= 0) return;
        int r = Math.min((int)radius, Math.min(iw, ih) / 2);
        if (activeContext == null) return;
        for (int row = 0; row < r; row++) {
            int offset = inset(row, ih, r);
            glQuad(ix + offset, iy + row, iw - offset * 2, 1, color);
            glQuad(ix + offset, iy + ih - row - 1, iw - offset * 2, 1, color);
        }
        glQuad(ix, iy + r, iw, ih - r * 2, color);
    }

    private static void glGradientRRect(float x, float y, float w, float h, float radius, int a, int b) {
        if (activeContext == null) return;
        int ix = (int) x, iy = (int) y, iw = (int) w, ih = (int) h;
        int r = Math.min((int) radius, Math.min(iw, ih) / 2);
        for (int column = 0; column < iw; column++) {
            int inset = inset(column, iw, r);
            int color = lerp(a, b, iw <= 1 ? 0f : column / (float) (iw - 1));
            glQuad(ix + column, iy + inset, 1, Math.max(0, ih - inset * 2), color);
        }
    }

    private static int inset(int row, int h, int r) {
        int d = Math.min(row, h - 1 - row);
        if (r <= 0 || d >= r) return 0;
        double dy = r - d - 0.5;
        return (int) Math.round(r - Math.sqrt(r * r - dy * dy));
    }

    private static void rrect(DrawContext g, int x, int y, int w, int h, int r, int color) {
        glBegin(); glRRect(x, y, w, h, r, color); glEnd();
    }

    private static void rrectStroke(DrawContext g, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        glBegin();
        glQuad(x + r, y, w - r * 2, 1, color);
        glQuad(x + r, y + h - 1, w - r * 2, 1, color);
        glQuad(x, y + r, 1, h - r * 2, color);
        glQuad(x + w - 1, y + r, 1, h - r * 2, color);
        for (int row = 0; row < r; row++) {
            int outer = inset(row, h, r);
            int next = row + 1 < r ? inset(row + 1, h, r) : 0;
            int edge = row == 0 ? r : Math.max(next, outer + 1);
            glQuad(x + outer, y + row, edge - outer, 1, color);
            glQuad(x + w - edge, y + row, edge - outer, 1, color);
            glQuad(x + outer, y + h - row - 1, edge - outer, 1, color);
            glQuad(x + w - edge, y + h - row - 1, edge - outer, 1, color);
        }
        glEnd();
    }

    /** Горизонтальный градиент со скруглением, рисуется колонками. */
    private static void gradRRect(DrawContext g, int x, int y, int w, int h, int r, int a, int b) {
        glBegin(); glGradientRRect(x, y, w, h, r, a, b); glEnd();
    }

    private static void iconPlay(DrawContext g, int cx, int cy, int h, int color) {
        int w = h * 3 / 4, x0 = cx - w / 3;
        for (int i = 0; i < w; i++) {
            int half = Math.round(h / 2f * (1f - i / (float) w));
            glQuad(x0 + i, cy - half, 1, Math.max(1, half * 2), color);
        }
    }

    private static void iconNext(DrawContext g, int cx, int cy, int h, int color) {
        // triangle pointing right + vertical bar
        int w = h * 3 / 5, x0 = cx - w / 2 - 2;
        for (int i = 0; i < w; i++) {
            int half = Math.round(h / 2f * (1f - i / (float) w));
            glQuad(x0 + i, cy - half, 1, Math.max(1, half * 2), color);
        }
        // vertical bar on the right
        glQuad(x0 + w + 2, cy - h / 2, 2, h, color);
    }

    private static void iconPrevious(DrawContext g, int cx, int cy, int h, int color) {
        // vertical bar + triangle pointing left
        int w = h * 3 / 5, x0 = cx - w / 2 + 2;
        // vertical bar on the left
        glQuad(x0 - 4, cy - h / 2, 2, h, color);
        // triangle pointing left
        for (int i = 0; i < w; i++) {
            int half = Math.round(h / 2f * (i / (float) w));
            glQuad(x0 + i, cy - half, 1, Math.max(1, half * 2), color);
        }
    }

    private static int lerp(int a, int b, float t) {
        int out = 0;
        for (int s = 24; s >= 0; s -= 8) {
            int ca = (a >>> s) & 255, cb = (b >>> s) & 255;
            out |= ((int) (ca + (cb - ca) * t) & 255) << s;
        }
        return out;
    }

    private static int brighten(int c) {
        return lerp(c, 0xFFFFFFFF, 0.18f);
    }

    // ---------- логика ----------

    private void search() {
        String q = search.trim();
        if (q.length() < 2 || searching) return;
        likedMode = false;
        scroll = 0;
        long now = System.currentTimeMillis();
        if (now - lastSearchAt < 400) return;
        lastSearchAt = now;
        searching = true;
        status = "Searching YouTube and open catalogs...";
        tracks.clear();
        InfernaMusicClient.JAMENDO.search(q).whenComplete((result, error) -> client.execute(() -> {
            searching = false;
            if (error != null) {
                status = "ЖОПА: " + cleanError(error);
                return;
            }
            tracks.addAll(result);
            status = result.size() + " tracks found";
            scroll = 0;
        }));
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar() && search.length() < 80) {
            search += input.asString();
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            search();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.offsetByCodePoints(search.length(), -1));
            return true;
        }
        if (input.hasCtrlOrCmd() && key == GLFW.GLFW_KEY_A) {
            search = "";
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        L l = layout();

        int navX = l.px + 10;
        int navY = l.py + 18 + 40;
        if (inside(mx, my, navX, navY, 140, 26)) {
            likedMode = false;
            scroll = 0;
            return true;
        }
        if (inside(mx, my, navX, navY + 32, 140, 26)) {
            likedMode = true;
            scroll = 0;
            return true;
        }

        if (inside(mx, my, l.searchBtnX(), l.searchBtnY(), 52, 16)) {
            search();
            return true;
        }

        List<JamendoTrack> visibleTracks = visibleTracks();
        if (!visibleTracks.isEmpty() && (!searching || likedMode)
                && inside(mx, my, l.mainX, l.listY, l.mainW, l.listBottom - l.listY)) {
            int index = (int) ((my - l.listY + scroll) / rowPitch);
            if (index >= 0 && index < visibleTracks.size()) {
                int ry = l.listY + index * rowPitch - (int) scroll;
                if (my <= ry + rowHeight) {
                    JamendoTrack track = visibleTracks.get(index);
                    int likeX = l.mainX + l.mainW - 49, likeY = ry + 8;
                    if (inside(mx, my, likeX, likeY, 16, 18)) {
                        if (track.isPlayable()) toggleLiked(track);
                        return true;
                    }
                    if (!track.isPlayable()) {
                        status = "Видео найдено, но аудиопоток недоступен: " + track.name();
                        return true;
                    }
                    if (likedMode) {
                        InfernaMusicClient.PLAYER.playPlaylist(visibleTracks, index);
                    } else {
                        InfernaMusicClient.PLAYER.play(track);
                    }
                    status = "Playing " + track.name();
                    return true;
                }
            }
        }

        if (inside(mx, my, l.volX - 4, l.volY - 4, 98, 16)) {
            draggingVolume = true;
            setVolume(mx, l.volX);
            return true;
        }

        if (inside(mx, my, l.barX() - 4, l.barY() - 8, 228, 20)) {
            timelinePreview = timelinePosition(mx, l);
            draggingTimeline = true;
            return true;
        }

        if (inside(mx, my, l.previousX(), l.footY + 10, 22, 22)) {
            skipTrack(-1);
            return true;
        }
        if (inside(mx, my, l.nextX(), l.footY + 10, 22, 22)) {
            skipTrack(1);
            return true;
        }

        if (inside(mx, my, l.playX(), l.playY(), 26, 26)) {
            JamendoTrack cur = InfernaMusicClient.PLAYER.currentTrack();
            if (cur != null) {
                if (InfernaMusicClient.PLAYER.isPlaying()) {
                    InfernaMusicClient.PLAYER.pause();
                } else if (InfernaMusicClient.PLAYER.isPaused()) {
                    InfernaMusicClient.PLAYER.resume();
                } else {
                    InfernaMusicClient.PLAYER.play(cur);
                }
            }
            return true;
        }
        if (inside(mx, my, l.stopX(), l.stopY(), 18, 18)) {
            InfernaMusicClient.PLAYER.stop();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (draggingVolume && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            setVolume(click.x(), layout().volX);
            return true;
        }
        if (draggingTimeline && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            timelinePreview = timelinePosition(click.x(), layout());
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingVolume = false;
        if (draggingTimeline) {
            draggingTimeline = false;
            JamendoTrack current = InfernaMusicClient.PLAYER.currentTrack();
            if (current != null) {
                int total = current.duration();
                int decoded = InfernaMusicClient.PLAYER.decodedDurationSeconds();
                if (decoded > 0) total = decoded;
                if (total > 0) InfernaMusicClient.PLAYER.seek(Math.round(total * timelinePreview));
            }
        }
        return super.mouseReleased(click);
    }

    private static float timelinePosition(double mouseX, L l) {
        return Math.max(0f, Math.min(1f, (float) ((mouseX - l.barX()) / 220.0)));
    }

    private void skipTrack(int direction) {
        List<JamendoTrack> list = visibleTracks();
        if (list.isEmpty()) return;
        JamendoTrack current = InfernaMusicClient.PLAYER.currentTrack();
        int index = -1;
        for (int i = 0; i < list.size(); i++) {
            if (sameTrack(list.get(i), current)) {
                index = i;
                break;
            }
        }
        int next = index < 0 ? 0 : Math.max(0, Math.min(list.size() - 1, index + direction));
        InfernaMusicClient.PLAYER.playPlaylist(list, next);
    }

    private void setVolume(double mouseX, int x) {
        float v = (float) Math.max(0, Math.min(1, (mouseX - x) / volumeWidth));
        InfernaMusicClient.PLAYER.setVolume(v);
    }

    private List<JamendoTrack> visibleTracks() {
        return likedMode ? InfernaMusicClient.CONFIG.likedTracks : tracks;
    }

    private static boolean isLiked(JamendoTrack track) {
        return InfernaMusicClient.CONFIG.likedTracks.stream().anyMatch(saved -> sameTrack(saved, track));
    }

    private static void toggleLiked(JamendoTrack track) {
        List<JamendoTrack> likedTracks = InfernaMusicClient.CONFIG.likedTracks;
        for (int i = 0; i < likedTracks.size(); i++) {
            if (sameTrack(likedTracks.get(i), track)) {
                likedTracks.remove(i);
                InfernaMusicClient.CONFIG.save();
                return;
            }
        }
        likedTracks.add(track);
        InfernaMusicClient.CONFIG.save();
    }

    private static boolean sameTrack(JamendoTrack first, JamendoTrack second) {
        if (first == null || second == null) return false;
        String firstUrl = first.audioUrl();
        String secondUrl = second.audioUrl();
        if (firstUrl != null && !firstUrl.isBlank() && secondUrl != null && !secondUrl.isBlank()) {
            return firstUrl.equalsIgnoreCase(secondUrl);
        }
        return first.id() == second.id()
                && Objects.equals(first.name(), second.name())
                && Objects.equals(first.artist(), second.artist());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        L l = layout();
        if (mouseY < l.listY || mouseY > l.listBottom) return true;
        int count = visibleTracks().size();
        float max = Math.max(0, count * rowPitch - (l.listBottom - l.listY));
        scroll = Math.max(0, Math.min(max, scroll - (float) verticalAmount * l.u(42)));
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private static boolean inside(double x, double y, float rx, float ry, float rw, float rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String trimToWidth(String value, int width) {
        if (value == null) return "";
        if (textRenderer.getWidth(value) <= width) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 0 && textRenderer.getWidth(value.substring(0, end) + suffix) > width) end--;
        return value.substring(0, end) + suffix;
    }

    private static String formatSeconds(int seconds) {
        seconds = Math.max(0, seconds);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String cleanError(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : trim(msg, 55);
    }
}
