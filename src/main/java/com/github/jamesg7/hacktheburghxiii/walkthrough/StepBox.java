package com.github.jamesg7.hacktheburghxiii.walkthrough;

import com.intellij.icons.AllIcons;
import com.intellij.markdown.utils.MarkdownToHtmlConverterKt;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/**
 * The box shown above the current step's code: "Step 3 of 9 · title", playback buttons, the
 * AI's note, the call stack and the output so far (this step's output emphasised).
 */
final class StepBox extends JPanel {
  interface Controls {
    void previous();
    void togglePlay();
    void next();
    void close();
  }

  record Colors(Color background, Color border, Color text, Color muted, Color accent, Color code) {}

  /** Everything the box shows. {@code outputBefore} is earlier output; {@code outputNow} is this step's. */
  record Content(String counter, @Nullable String title, @Nullable String note, List<String> stack,
                 String outputBefore, String outputNow, boolean playing, boolean hasPrevious, boolean hasNext) {}

  private static final int MAX_WIDTH = 520;
  private static final int OUTPUT_LINES = 6;
  private final JEditorPane body = new JEditorPane();
  private @Nullable Inlay<?> inlay;

  StepBox(Content content, Colors colors, String codeFont, int indent, Controls controls) {
    super(new BorderLayout());
    setOpaque(false);
    setBorder(BorderFactory.createEmptyBorder(JBUI.scale(3), indent, JBUI.scale(3), 0));

    JPanel card = new Card(colors);
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
    card.setBorder(JBUI.Borders.empty(5, 10, 8, 6));

    JPanel header = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    header.setOpaque(false);
    header.setAlignmentX(Component.LEFT_ALIGNMENT);
    JBLabel heading = new JBLabel(content.counter() + (content.title() == null ? "" : "  ·  " + content.title()));
    heading.setFont(JBFont.label().deriveFont(Font.BOLD));
    heading.setForeground(colors.text());
    header.add(heading, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0));
    buttons.setOpaque(false);
    InplaceButton previous = new InplaceButton("Previous step", AllIcons.Actions.Play_back, e -> controls.previous());
    previous.setEnabled(content.hasPrevious());
    InplaceButton play = new InplaceButton(content.playing() ? "Pause" : "Play",
        content.playing() ? AllIcons.Actions.Pause : AllIcons.Actions.Execute, e -> controls.togglePlay());
    InplaceButton next = new InplaceButton("Next step", AllIcons.Actions.Play_forward, e -> controls.next());
    next.setEnabled(content.hasNext());
    buttons.add(previous);
    buttons.add(play);
    buttons.add(next);
    buttons.add(new InplaceButton("Close walkthrough", AllIcons.Actions.Close, e -> controls.close()));
    header.add(buttons, BorderLayout.EAST);
    card.add(header);

    String html = bodyHtml(content, colors);
    if (html != null) {
      setUpBody(colors, codeFont);
      body.setText(html);
      fitBody();
      card.add(body);
    }
    add(card, BorderLayout.WEST);
  }

  void setInlay(@Nullable Inlay<?> inlay) {
    this.inlay = inlay;
  }

  private static @Nullable String bodyHtml(Content content, Colors colors) {
    StringBuilder html = new StringBuilder();
    if (content.note() != null && !content.note().isBlank()) html.append(markdown(content.note()));
    if (content.stack().size() > 1) {
      html.append("<p class=\"meta\">Call stack: ");
      for (int i = 0; i < content.stack().size(); i++) {
        if (i > 0) html.append(" → ");
        String frame = StringUtil.escapeXmlEntities(content.stack().get(i));
        html.append(i == content.stack().size() - 1 ? "<b>" + frame + "</b>" : frame);
      }
      html.append("</p>");
    }
    if (!content.outputBefore().isEmpty() || !content.outputNow().isEmpty()) {
      // Only the last few lines, so the box stays small.
      String before = lastLines(content.outputBefore(), Math.max(0, OUTPUT_LINES - lineCount(content.outputNow())));
      html.append("<p class=\"meta\">Output:</p><pre>").append(StringUtil.escapeXmlEntities(before));
      if (!content.outputNow().isEmpty()) {
        html.append("<span class=\"new\">").append(StringUtil.escapeXmlEntities(content.outputNow())).append("</span>");
      }
      html.append("</pre>");
    }
    return html.isEmpty() ? null : "<html><body>" + html + "</body></html>";
  }

  private void setUpBody(Colors colors, String codeFont) {
    StyleSheet styles = new StyleSheet();
    String code = "font-family: '" + codeFont + "'; background-color: " + ColorUtil.toHtmlColor(colors.code()) + ";";
    styles.addRule("body { color: " + ColorUtil.toHtmlColor(colors.text()) + "; margin: 0; }");
    styles.addRule("p { margin-top: 2px; margin-bottom: 4px; }");
    styles.addRule(".meta { color: " + ColorUtil.toHtmlColor(colors.muted()) + "; margin-bottom: 1px; }");
    styles.addRule("code { " + code + " }");
    styles.addRule("pre { " + code + " padding: 3px; margin-top: 0; margin-bottom: 2px; }");
    styles.addRule(".new { color: " + ColorUtil.toHtmlColor(colors.accent()) + "; font-weight: bold; }");
    styles.addRule("ul, ol { margin-top: 0; margin-bottom: 4px; margin-left: 16px; }");
    HTMLEditorKit kit = new HTMLEditorKitBuilder().withWordWrapViewFactory().withStyleSheet(styles).build();
    body.setEditorKit(kit);
    body.setEditable(false);
    body.setOpaque(false);
    body.setBorder(JBUI.Borders.emptyTop(3));
    body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    body.setFont(JBFont.label());
    body.setAlignmentX(Component.LEFT_ALIGNMENT);
    body.addHyperlinkListener(e -> {
      if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
        com.intellij.ide.BrowserUtil.browse(e.getURL());
      }
    });
  }

  /** Sizes the text to its natural width, capped at MAX_WIDTH, and the height that needs. */
  private void fitBody() {
    int maxWidth = JBUIScale.scale(MAX_WIDTH);
    body.setSize(new Dimension(maxWidth, Short.MAX_VALUE));
    int width = Math.min(maxWidth, body.getPreferredSize().width);
    body.setSize(new Dimension(width, Short.MAX_VALUE));
    Dimension size = new Dimension(width, body.getPreferredSize().height);
    body.setPreferredSize(size);
    body.setMaximumSize(size);
  }

  private static String markdown(String text) {
    return MarkdownToHtmlConverterKt.convertMarkdownToHtml(text).replaceAll("(?i)</?body>", "");
  }

  private static int lineCount(String text) {
    return text.isEmpty() ? 0 : text.split("\n", -1).length - (text.endsWith("\n") ? 1 : 0);
  }

  private static String lastLines(String text, int count) {
    if (count == 0 || text.isEmpty()) return "";
    String[] lines = text.split("\n", -1);
    int end = text.endsWith("\n") ? lines.length - 1 : lines.length;
    int start = Math.max(0, end - count);
    StringBuilder out = new StringBuilder(start > 0 ? "…\n" : "");
    for (int i = start; i < end; i++) out.append(lines[i]).append('\n');
    return out.toString();
  }

  /** Paints the rounded background and border. */
  private static final class Card extends JPanel {
    private final Colors colors;

    Card(Colors colors) {
      this.colors = colors;
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = JBUIScale.scale(10);
        g2.setColor(colors.background());
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.setColor(colors.border());
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      } finally {
        g2.dispose();
      }
    }

    @Override
    public Dimension getMaximumSize() {
      return getPreferredSize();
    }
  }
}
