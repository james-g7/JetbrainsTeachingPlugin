package com.github.jamesg7.hacktheburghxiii.util;

import com.intellij.icons.AllIcons;
import com.intellij.markdown.utils.MarkdownToHtmlConverterKt;
import com.intellij.openapi.editor.Inlay;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.github.jamesg7.hacktheburghxiii.explain.ExplanationNode;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
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
import java.awt.event.ActionListener;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The explanation box shown above highlighted code: a title bar, the node's Markdown text, and
 * links for hints, the answer, tour navigation and asking a question.
 */
final class ExplanationBox extends JPanel {
  /** What the box's controls do; implemented by {@link HighlightUtil}. */
  interface Actions {
    void link(String href, String label);
    void next();
    void back();
    void close();
    void ask();
    void hintRevealed(int index);
    void answerRevealed();
  }

  record Colors(Color background, Color border, Color text, Color link, Color deadLink, Color code) {}

  /** What the box should offer, decided by the caller from the store's state. */
  record Options(boolean hasBack, boolean hasNext, boolean canAsk, int maxWidth, @Nullable Integer fontSize,
                 String codeFontFamily, Predicate<String> isLinkLive) {}

  private static final Pattern ANCHOR = Pattern.compile("<a\\s+href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
  private static final int ARC = 10;

  private final ExplanationNode node;
  private final Colors colors;
  private final Options options;
  private final Actions actions;
  private final JEditorPane body = new JEditorPane();
  private final JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0));
  private int hintsShown;
  private boolean answerShown;
  private @Nullable Inlay<?> inlay;

  ExplanationBox(ExplanationNode node, Colors colors, Options options, int indent, Actions actions) {
    super(new BorderLayout());
    this.node = node;
    this.colors = colors;
    this.options = options;
    this.actions = actions;
    setOpaque(false);
    setBorder(BorderFactory.createEmptyBorder(JBUI.scale(3), indent, JBUI.scale(3), 0));

    JPanel card = new Card();
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
    card.setBorder(JBUI.Borders.empty(6, 10, 8, 8));
    card.add(header());
    card.add(Box.createVerticalStrut(JBUI.scale(2)));
    setUpBody();
    card.add(body);
    footer.setOpaque(false);
    footer.setAlignmentX(Component.LEFT_ALIGNMENT);
    card.add(footer);
    add(card, BorderLayout.WEST);

    refresh();
  }

  void setInlay(@Nullable Inlay<?> inlay) {
    this.inlay = inlay;
  }

  private JComponent header() {
    JPanel header = new JPanel(new BorderLayout(JBUI.scale(6), 0));
    header.setOpaque(false);
    header.setAlignmentX(Component.LEFT_ALIGNMENT);
    if (node.title() != null || node.kind().icon() != null) {
      JLabel title = new JLabel(node.title() == null ? "" : node.title(), node.kind().icon(), JLabel.LEFT);
      title.setFont(baseFont().deriveFont(Font.BOLD));
      title.setForeground(colors.text());
      header.add(title, BorderLayout.CENTER);
    }
    InplaceButton close = new InplaceButton("Close", AllIcons.Actions.Close, e -> actions.close());
    header.add(close, BorderLayout.EAST);
    return header;
  }

  private void setUpBody() {
    StyleSheet styles = new StyleSheet();
    String text = ColorUtil.toHtmlColor(colors.text());
    String code = "font-family: '" + options.codeFontFamily() + "'; background-color: " + ColorUtil.toHtmlColor(colors.code()) + ";";
    styles.addRule("body { color: " + text + "; margin: 0; }");
    styles.addRule("p { margin-top: 0; margin-bottom: 4px; }");
    styles.addRule("a { color: " + ColorUtil.toHtmlColor(colors.link()) + "; }");
    styles.addRule(".dead { color: " + ColorUtil.toHtmlColor(colors.deadLink()) + "; }");
    styles.addRule("code { " + code + " }");
    styles.addRule("pre { " + code + " padding: 4px; margin-top: 2px; margin-bottom: 4px; }");
    styles.addRule("h1, h2, h3, h4 { margin-top: 2px; margin-bottom: 2px; }");
    styles.addRule("ul, ol { margin-top: 0; margin-bottom: 4px; margin-left: 16px; }");
    styles.addRule(".reveal { margin-top: 6px; }");
    HTMLEditorKit kit = new HTMLEditorKitBuilder().withWordWrapViewFactory().withStyleSheet(styles).build();

    body.setEditorKit(kit);
    body.setEditable(false);
    body.setOpaque(false);
    body.setBorder(null);
    body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    body.setFont(baseFont());
    body.setAlignmentX(Component.LEFT_ALIGNMENT);
    body.addHyperlinkListener(e -> {
      if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getDescription() != null) {
        actions.link(e.getDescription(), linkLabel(e));
      }
    });
  }

  private Font baseFont() {
    JBFont font = JBFont.label();
    return options.fontSize() == null ? font : font.deriveFont((float) JBUIScale.scale(options.fontSize()));
  }

  private String linkLabel(HyperlinkEvent e) {
    Element element = e.getSourceElement();
    if (element == null) return e.getDescription();
    try {
      return body.getDocument().getText(element.getStartOffset(), element.getEndOffset() - element.getStartOffset()).trim();
    } catch (BadLocationException ex) {
      return e.getDescription();
    }
  }

  /** Re-renders the text and footer (e.g. after a hint is revealed) and resizes the box. */
  private void refresh() {
    StringBuilder html = new StringBuilder("<html><body>").append(markdown(node.text()));
    for (int i = 0; i < hintsShown; i++) {
      String label = node.hints().size() > 1 ? "Hint " + (i + 1) : "Hint";
      html.append("<div class=\"reveal\"><b>").append(label).append(":</b> ").append(markdown(node.hints().get(i))).append("</div>");
    }
    if (answerShown && node.answer() != null) {
      html.append("<div class=\"reveal\"><b>Answer:</b> ").append(markdown(node.answer())).append("</div>");
    }
    body.setText(html.append("</body></html>").toString());
    body.setVisible(!node.text().isBlank() || hintsShown > 0 || answerShown);

    rebuildFooter();
    fitBody();
    revalidate();
    repaint();
    if (inlay != null && inlay.isValid()) inlay.update();
  }

  private void rebuildFooter() {
    footer.removeAll();
    if (hintsShown < node.hints().size()) {
      String label = node.hints().size() > 1 ? "Show hint " + (hintsShown + 1) + " of " + node.hints().size() : "Show hint";
      footer.add(link(label, e -> {
        int index = hintsShown++;
        actions.hintRevealed(index);
        refresh();
      }));
    }
    if (node.answer() != null && !answerShown) {
      footer.add(link("Show answer", e -> {
        answerShown = true;
        actions.answerRevealed();
        refresh();
      }));
    }
    if (options.hasBack()) footer.add(link("← Back", e -> actions.back()));
    if (options.hasNext()) footer.add(link("Next →", e -> actions.next()));
    if (options.canAsk()) footer.add(link("Ask a question…", e -> actions.ask()));
    footer.setVisible(footer.getComponentCount() > 0);
    if (footer.isVisible()) footer.setBorder(JBUI.Borders.emptyTop(4));
  }

  private static ActionLink link(String text, ActionListener listener) {
    return new ActionLink(text, listener);
  }

  /** Sizes the text to its natural width, capped at maxWidth, and the height that needs. */
  private void fitBody() {
    int maxWidth = JBUIScale.scale(options.maxWidth());
    body.setPreferredSize(null);
    body.setSize(new Dimension(maxWidth, Short.MAX_VALUE));
    int width = Math.min(maxWidth, body.getPreferredSize().width);
    body.setSize(new Dimension(width, Short.MAX_VALUE));
    Dimension size = new Dimension(width, body.getPreferredSize().height);
    body.setPreferredSize(size);
    body.setMaximumSize(size);
  }

  /**
   * Converts Markdown to HTML. Links the user can't follow (an unknown node with no provider to
   * generate it) are turned into dimmed text.
   */
  private String markdown(String markdown) {
    String html = MarkdownToHtmlConverterKt.convertMarkdownToHtml(markdown).replaceAll("(?i)</?body>", "");
    Matcher m = ANCHOR.matcher(html);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String replacement = options.isLinkLive().test(m.group(1)) ? m.group() : "<span class=\"dead\">" + m.group(2) + "</span>";
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  /** Paints the rounded background and border behind the box's contents. */
  private final class Card extends JPanel {
    Card() {
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = JBUIScale.scale(ARC);
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
