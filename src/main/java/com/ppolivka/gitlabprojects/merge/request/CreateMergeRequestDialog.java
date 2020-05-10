package com.ppolivka.gitlabprojects.merge.request;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SortedComboBoxModel;
import com.intellij.util.containers.Convertor;
import com.ppolivka.gitlabprojects.component.SearchBoxModel;
import com.ppolivka.gitlabprojects.configuration.ProjectState;
import com.ppolivka.gitlabprojects.configuration.SettingsState;
import com.ppolivka.gitlabprojects.merge.info.BranchInfo;
import com.ppolivka.gitlabprojects.util.GitLabUtil;
import org.apache.commons.lang.StringUtils;
import org.gitlab.api.models.GitlabUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ItemEvent;
import java.io.IOException;

import static com.ppolivka.gitlabprojects.util.MessageUtil.showErrorDialog;

/**
 * Dialog fore creating merge requests
 *
 * @author ppolivka
 * @since 30.10.2015
 */
public class CreateMergeRequestDialog extends DialogWrapper {

    private Project project;

    private JPanel mainView;
    private JComboBox targetBranch;
    private JLabel currentBranch;
    private JTextField mergeTitle;
    private JTextArea mergeDescription;
    private JButton diffButton;
    private JComboBox assigneeBox;
    private JCheckBox removeSourceBranch;
    private JCheckBox wip;
    private JComboBox templateBox;
    private JButton assignMe;

    private SortedComboBoxModel<BranchInfo> myBranchModel;
    private BranchInfo lastSelectedBranch;

    final ProjectState projectState;

    @NotNull
    final GitLabCreateMergeRequestWorker mergeRequestWorker;

    public CreateMergeRequestDialog(@Nullable Project project, @NotNull GitLabCreateMergeRequestWorker gitLabMergeRequestWorker) {
        super(project);
        this.project = project;
        projectState = ProjectState.getInstance(project);
        mergeRequestWorker = gitLabMergeRequestWorker;
        init();

    }

    @Override
    protected void init() {
        super.init();
        setTitle("Create Merge Request");
        setVerticalStretch(2f);

        SearchBoxModel searchBoxModel = new SearchBoxModel(assigneeBox, mergeRequestWorker.getSearchableUsers());
        assigneeBox.setModel(searchBoxModel);
        assigneeBox.setEditable(true);
        assigneeBox.addItemListener(searchBoxModel);
        assigneeBox.setBounds(140, 170, 180, 20);

        assignMe.addActionListener(event -> {
            GitLabUtil.computeValueInModal(project, "Changing assignee...", (Convertor<ProgressIndicator, Void>) o -> {
                try {
                    SettingsState settingsState = SettingsState.getInstance();
                    GitlabUser currentUser = settingsState.api(mergeRequestWorker.getGitRepository()).getCurrentUser();
                    assigneeBox.setSelectedItem(new SearchableUser(currentUser));
                } catch (Exception e) {
                    showErrorDialog(project, "Cannot change assignee of this merge request.", "Cannot Change Assignee");
                }
                return null;
            });
        });

        templateBox.setEditable(true);
        templateBox.addItem("None");

        VirtualFile gitlabIssueTemplatesRoot = mergeRequestWorker.getGitlabIssueTemplatesRoot();
        if (gitlabIssueTemplatesRoot != null) {
            for (VirtualFile file : gitlabIssueTemplatesRoot.getChildren()) {
                if (!file.isDirectory()) {
                    templateBox.addItem(file.getName());
                }
            }
        }

        templateBox.addItemListener(itemEvent -> {
            if (ItemEvent.SELECTED == itemEvent.getStateChange()) {
                String selectedItem = itemEvent.getItem().toString();
                if (gitlabIssueTemplatesRoot != null) {
                    VirtualFile fileByRelativePath = gitlabIssueTemplatesRoot.findFileByRelativePath(selectedItem);
                    if (fileByRelativePath != null && fileByRelativePath.exists()) {
                        try {
                            String text = VfsUtil.loadText(fileByRelativePath);
                            mergeDescription.setText(text);
                            return;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                mergeDescription.setText("");
            }
        });


        currentBranch.setText(mergeRequestWorker.getGitLocalBranch().getName());

        myBranchModel = new SortedComboBoxModel<>((o1, o2) -> StringUtil.naturalCompare(o1.getName(), o2.getName()));
        myBranchModel.setAll(mergeRequestWorker.getBranches());
        targetBranch.setModel(myBranchModel);
        targetBranch.setSelectedIndex(0);
        if (mergeRequestWorker.getLastUsedBranch() != null) {
            targetBranch.setSelectedItem(mergeRequestWorker.getLastUsedBranch());
        }
        lastSelectedBranch = getSelectedBranch();

        targetBranch.addActionListener(e -> {
            prepareTitle();
            lastSelectedBranch = getSelectedBranch();
            projectState.setLastMergedBranch(getSelectedBranch().getName());
            mergeRequestWorker.getDiffViewWorker().launchLoadDiffInfo(mergeRequestWorker.getLocalBranchInfo(), getSelectedBranch());
        });

        prepareTitle();

        Boolean deleteMergedBranch = projectState.getDeleteMergedBranch();
        if(deleteMergedBranch != null && deleteMergedBranch) {
            this.removeSourceBranch.setSelected(true);
        }

        Boolean mergeAsWorkInProgress = projectState.getMergeAsWorkInProgress();
        if(mergeAsWorkInProgress != null && mergeAsWorkInProgress) {
            this.wip.setSelected(true);
        }

        diffButton.addActionListener(e -> mergeRequestWorker.getDiffViewWorker().showDiffDialog(mergeRequestWorker.getLocalBranchInfo(), getSelectedBranch()));
    }

    @Override
    protected void doOKAction() {
        BranchInfo branch = getSelectedBranch();
        if (mergeRequestWorker.checkAction(branch)) {
            String title = mergeTitle.getText();
            if(wip.isSelected()) {
                title = "WIP:"+title;
            }
            mergeRequestWorker.createMergeRequest(branch, getAssignee(), title, mergeDescription.getText(), removeSourceBranch.isSelected());
            super.doOKAction();
        }
    }

    @Nullable
    @Override
    protected ValidationInfo doValidate() {
        if (StringUtils.isBlank(mergeTitle.getText())) {
            return new ValidationInfo("Merge title cannot be empty", mergeTitle);
        }
        if (getSelectedBranch().getName().equals(currentBranch.getText())) {
            return new ValidationInfo("Target branch must be different from current branch.", targetBranch);
        }
        return null;
    }

    private BranchInfo getSelectedBranch() {
        return (BranchInfo) targetBranch.getSelectedItem();
    }

    @Nullable
    private GitlabUser getAssignee() {
        SearchableUser searchableUser = (SearchableUser) this.assigneeBox.getSelectedItem();
        if(searchableUser != null) {
            return searchableUser.getGitLabUser();
        }
        return null;
    }

    private void prepareTitle() {
        if (StringUtils.isBlank(mergeTitle.getText()) || mergeTitleGenerator(lastSelectedBranch).equals(mergeTitle.getText())) {
            mergeTitle.setText(mergeTitleGenerator(getSelectedBranch()));
        }
    }

    private String mergeTitleGenerator(BranchInfo branchInfo) {
        String lastCommitMessage = mergeRequestWorker.getLastCommitMessage();
        if (lastCommitMessage != null && !"".equals(lastCommitMessage)) {
            return lastCommitMessage;
        }
        return "Merge of " + currentBranch.getText() + " to " + branchInfo;
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return mainView;
    }
}
