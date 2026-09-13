package io.titan.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Dimension;

public final class TitanSqlPreviewPopup {
    private TitanSqlPreviewPopup() {
    }

    public static void show(@NotNull PsiMethod method) {
        if (!TitanSqlPreviewGenerator.supports(method)) {
            return;
        }

        Project project = method.getProject();
        TitanSqlPreviewGenerator.SqlPreview preview = TitanSqlPreviewGenerator.generate(method);
        JComponent content = buildSideBySidePanel(preview);

        JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setProject(project)
                .setTitle("Titan SQL Preview: " + method.getName())
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()
                .showInFocusCenter();
    }

    private static JComponent buildSideBySidePanel(TitanSqlPreviewGenerator.SqlPreview preview) {
        JBTextArea pgArea = new JBTextArea(preview.postgresql());
        pgArea.setEditable(false);
        pgArea.setLineWrap(false);

        JBTextArea myArea = new JBTextArea(preview.mysql());
        myArea.setEditable(false);
        myArea.setLineWrap(false);

        JPanel left = new JPanel(new BorderLayout());
        left.add(new JBScrollPane(pgArea), BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout());
        right.add(new JBScrollPane(myArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(980, 560));
        return panel;
    }
}
