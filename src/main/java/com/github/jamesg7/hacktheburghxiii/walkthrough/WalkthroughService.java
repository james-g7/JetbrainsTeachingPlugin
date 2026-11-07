package com.github.jamesg7.hacktheburghxiii.walkthrough;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.Timer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Plays a {@link Walkthrough} in the editor. One per project; call on the EDT.
 *
 * <pre>{@code
 * WalkthroughService walkthroughs = WalkthroughService.getInstance(project);
 * walkthroughs.setProvider(myAi);        // used by Tools → Learning Agent → Walk Through Function…
 * walkthroughs.showJson(json);           // or play JSON directly
 * }</pre>
 */
@Service(Service.Level.PROJECT)
public final class WalkthroughService implements Disposable {
  private final Project project;
  private final WalkthroughPresenter presenter;
  private final Timer timer;
  private volatile @Nullable WalkthroughProvider provider;
  private @Nullable Walkthrough walkthrough;
  private int position;
  private int generation;

  public WalkthroughService(Project project) {
    this.project = project;
    this.presenter = new WalkthroughPresenter(project);
    this.timer = new Timer(Walkthrough.DEFAULT_DELAY, e -> advance());
  }

  public static WalkthroughService getInstance(Project project) {
    return project.getService(WalkthroughService.class);
  }

  // ---- Starting ----

  public void setProvider(@Nullable WalkthroughProvider provider) {
    this.provider = provider;
  }

  public boolean hasProvider() {
    return provider != null;
  }

  /** Parses and plays a walkthrough. Throws IllegalArgumentException (naming the bad step) if it's invalid. */
  public void showJson(String json) {
    start(Walkthrough.fromJson(json));
  }

  public void start(Walkthrough walkthrough) {
    timer.stop();
    generation++;
    this.walkthrough = walkthrough;
    position = 0;
    timer.setDelay(walkthrough.delay());
    timer.setInitialDelay(walkthrough.delay());
    render();
  }

  /** Asks the provider for a walkthrough and plays it when it arrives. */
  public void generate(WalkthroughProvider.Request request) {
    WalkthroughProvider current = provider;
    if (current == null) {
      Messages.showInfoMessage(project, "No AI is connected to generate walkthroughs yet. Register a WalkthroughProvider, "
          + "or play a JSON file with Tools → Learning Agent → Show Walkthrough.", "Walkthrough");
      return;
    }
    int requested = ++generation;
    setStatus("Generating a walkthrough of " + request.function() + "…");
    current.generate(project, request).whenComplete((result, error) -> ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) return;
      setStatus("");
      if (requested != generation) return; // something else started meanwhile
      if (error != null) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        Messages.showErrorDialog(project, "Couldn't generate the walkthrough: " + cause.getMessage(), "Walkthrough");
      } else if (result != null) {
        start(result);
      }
    }));
  }

  // ---- Playback ----

  public void goTo(int position) {
    if (walkthrough == null) return;
    this.position = Math.max(0, Math.min(position, walkthrough.steps().size() - 1));
    render();
  }

  public void next() {
    goTo(position + 1);
  }

  public void previous() {
    goTo(position - 1);
  }

  public void togglePlay() {
    if (walkthrough == null) return;
    if (timer.isRunning()) {
      timer.stop();
    } else {
      if (position >= walkthrough.steps().size() - 1) position = 0;
      timer.start();
    }
    render();
  }

  /** Ends the walkthrough and removes it from the editor. */
  public void stop() {
    timer.stop();
    walkthrough = null;
    presenter.clear();
  }

  private void advance() {
    if (walkthrough == null || position >= walkthrough.steps().size() - 1) {
      timer.stop();
      render();
      return;
    }
    position++;
    if (walkthrough.steps().get(position).pause()) timer.stop();
    render();
  }

  // ---- Rendering ----

  private void render() {
    Walkthrough w = walkthrough;
    if (w == null) return;
    List<Walkthrough.Step> steps = w.steps();
    Walkthrough.Step step = steps.get(position);

    StringBuilder before = new StringBuilder();
    for (int i = 0; i < position; i++) before.append(steps.get(i).output());
    String title = step.title() != null ? step.title() : position == 0 && !w.title().isEmpty() ? w.title() : null;
    StepBox.Content content = new StepBox.Content(
        "Step " + (position + 1) + " of " + steps.size(), title, step.note(), step.stack(),
        before.toString(), step.output(), timer.isRunning(), position > 0, position < steps.size() - 1);

    presenter.show(step, changed(steps, position), content, new StepBox.Controls() {
      @Override
      public void previous() {
        timer.stop();
        WalkthroughService.this.previous();
      }

      @Override
      public void togglePlay() {
        WalkthroughService.this.togglePlay();
      }

      @Override
      public void next() {
        timer.stop();
        WalkthroughService.this.next();
      }

      @Override
      public void close() {
        stop();
      }
    });
  }

  /**
   * The step's {@code changed} list if the AI gave one; otherwise variables that are new or differ
   * from the last earlier step in the same function call (same stack).
   */
  private static Set<String> changed(List<Walkthrough.Step> steps, int position) {
    Walkthrough.Step step = steps.get(position);
    if (step.changed() != null) return new HashSet<>(step.changed());
    Set<String> changed = new HashSet<>();
    for (int i = position - 1; i >= 0; i--) {
      Walkthrough.Step earlier = steps.get(i);
      if (!Objects.equals(earlier.stack(), step.stack())) continue;
      Map<String, String> previous = earlier.vars();
      step.vars().forEach((name, value) -> {
        if (!value.equals(previous.get(name))) changed.add(name);
      });
      return changed;
    }
    return changed; // first step in this call: nothing to compare with
  }

  private void setStatus(String text) {
    StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
    if (statusBar != null) statusBar.setInfo(text);
  }

  @Override
  public void dispose() {
    timer.stop();
    presenter.clear();
  }
}
