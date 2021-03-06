package com.itemis.jenkins.plugins.unleash;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import hudson.Extension;
import hudson.Launcher;
import hudson.maven.AbstractMavenProject;
import hudson.maven.MavenModuleSet;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import net.sf.json.JSONObject;

public class UnleashMavenBuildWrapper extends BuildWrapper {
  private static String ENV_VAR_SCM_USERNAME = "UNLEASH_SCM_USERNAME";
  private static String ENV_VAR_SCM_PASSWORD = "UNLEASH_SCM_PASSWORD";
  private static String ENV_VAR_SCM_SSH_PASSPHRASE = "UNLEASH_SCM_SSH_PASSPHRASE";

  private String goals = DescriptorImpl.DEFAULT_GOALS;
  private String profiles = DescriptorImpl.DEFAULT_PROFILES;
  private String releaseArgs = DescriptorImpl.DEFAULT_RELEASE_ARGS;
  private List<HookDescriptor> hooks = Lists.newArrayList();
  private boolean useLogTimestamps = DescriptorImpl.DEFAULT_USE_LOG_TIMESTAMPS;
  private String tagNamePattern = DescriptorImpl.DEFAULT_TAG_NAME_PATTERN;
  private String scmMessagePrefix = DescriptorImpl.DEFAULT_SCM_MESSAGE_PREFIX;
  private boolean preselectUseGlobalVersion = DescriptorImpl.DEFAULT_PRESELECT_USE_GLOBAL_VERSION;
  private boolean preselectAllowLocalReleaseArtifacts = DescriptorImpl.DEFAULT_PRESELECT_ALLOW_LOCAL_RELEASE_ARTIFACTS;
  private boolean preselectCommitBeforeTagging = DescriptorImpl.DEFAULT_PRESELECT_COMMIT_BEFORE_TAGGING;
  private String workflowPath = DescriptorImpl.DEFAULT_WORKFLOW_PATH;
  private String credentialsId;

  @DataBoundConstructor
  public UnleashMavenBuildWrapper(String goals, String profiles, String releaseArgs, boolean useLogTimestamps,
      String tagNamePattern, String scmMessagePrefix, boolean preselectUseGlobalVersion, List<HookDescriptor> hooks,
      boolean preselectAllowLocalReleaseArtifacts, boolean preselectCommitBeforeTagging, String workflowPath,
      String credentialsId) {
    super();
    this.goals = goals;
    this.profiles = profiles;
    this.releaseArgs = releaseArgs;
    this.useLogTimestamps = useLogTimestamps;
    this.tagNamePattern = tagNamePattern;
    this.scmMessagePrefix = scmMessagePrefix;
    this.preselectUseGlobalVersion = preselectUseGlobalVersion;
    this.hooks = hooks;
    this.preselectAllowLocalReleaseArtifacts = preselectAllowLocalReleaseArtifacts;
    this.preselectCommitBeforeTagging = preselectCommitBeforeTagging;
    this.workflowPath = workflowPath;
    this.credentialsId = credentialsId;
  }

  @Override
  public Environment setUp(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher, BuildListener listener)
      throws IOException, InterruptedException {
    if (!isReleaseBuild(build)) {
      return new Environment() {
        @Override
        public void buildEnvVars(Map<String, String> env) {
        }
      };
    }

    UnleashArgumentsAction arguments = build.getAction(UnleashArgumentsAction.class);
    StringBuilder command = new StringBuilder(getGoals());

    if (StringUtils.isNotBlank(this.workflowPath)) {
      // TODO handle absolute and relative paths!
      command.append(" -Dworkflow=").append(this.workflowPath);
    }

    // appends the profiles to the Maven call
    if (StringUtils.isNotBlank(getProfiles())) {
      Iterable<String> split = Splitter.on(',').split(getProfiles());
      List<String> profiles = Lists.newArrayList();
      for (String profile : split) {
        if (StringUtils.isNotBlank(profile)) {
          profiles.add(profile.trim());
        }
      }
      if (profiles.size() > 0) {
        String listedProfiles = Joiner.on(',').join(profiles);
        command.append(" -P").append(listedProfiles);
        command.append(" -Dunleash.profiles=").append(listedProfiles);
      }
    }

    if (StringUtils.isNotBlank(getReleaseArgs())) {
      command.append(" -Dunleash.releaseArgs=\"").append(getReleaseArgs().trim()).append("\"");
    }
    if (StringUtils.isNotBlank(getTagNamePattern())) {
      command.append(" -Dunleash.tagNamePattern=").append(getTagNamePattern().trim());
    }
    if (StringUtils.isNotBlank(getScmMessagePrefix())) {
      command.append(" -Dunleash.scmMessagePrefix=").append(getScmMessagePrefix().trim());
    }
    command.append(" -DenableLogTimestamps=").append(isUseLogTimestamps());

    if (this.hooks != null) {
      for (HookDescriptor hookData : this.hooks) {
        if (StringUtils.isNotBlank(hookData.getName()) && StringUtils.isNotBlank(hookData.getData())) {
          command.append(" -D").append(hookData.getName()).append("=\"").append(hookData.getData()).append("\"");
          if (StringUtils.isNotBlank(hookData.getRollbackData())) {
            command.append(" -D").append(hookData.getName()).append("-rollback=\"").append(hookData.getRollbackData())
                .append("\"");
          }
        }
      }
    }

    if (arguments != null) {
      if (arguments.useGlobalReleaseVersion()) {
        command.append(" -Dunleash.releaseVersion=").append(arguments.getGlobalReleaseVersion());
        command.append(" -Dunleash.developmentVersion=").append(arguments.getGlobalDevelopmentVersion());
      }
      command.append(" -Dunleash.allowLocalReleaseArtifacts=").append(arguments.allowLocalReleaseArtifacts());
      command.append(" -Dunleash.commitBeforeTagging=").append(arguments.commitBeforeTagging());
      if (arguments.errorLog()) {
        command.append(" -e");
      }
      if (arguments.debugLog()) {
        command.append(" -X");
      }
    }

    final Map<String, String> scmEnv = updateCommandWithScmCredentials(build, command);

    build.addAction(new UnleashArgumentInterceptorAction(command.toString()));
    build.addAction(new UnleashBadgeAction());

    return new Environment() {
      @Override
      public void buildEnvVars(Map<String, String> env) {
        // TODO maybe add an environment variable indicating a release build
        env.putAll(scmEnv);
      }
    };
  }

  private Map<String, String> updateCommandWithScmCredentials(AbstractBuild build, StringBuilder command) {
    String scmUsername = null;
    String scmPassword = null;
    String scmSshPassphrase = null;
    if (StringUtils.isNotBlank(this.credentialsId)) {
      StandardUsernameCredentials credentials = CredentialsProvider.findCredentialById(this.credentialsId,
          StandardUsernameCredentials.class, build, URIRequirementBuilder.create().build());
      if (credentials instanceof StandardUsernamePasswordCredentials) {
        StandardUsernamePasswordCredentials c = (StandardUsernamePasswordCredentials) credentials;
        scmUsername = c.getUsername();
        scmPassword = c.getPassword().getPlainText();
      } else if (credentials instanceof SSHUserPrivateKey) {
        SSHUserPrivateKey c = (SSHUserPrivateKey) credentials;
        Secret passphrase = c.getPassphrase();
        scmSshPassphrase = passphrase != null ? passphrase.getPlainText() : null;
      }
    }

    final Map<String, String> scmEnv = Maps.newHashMap();
    if (scmUsername != null) {
      command.append(" -Dunleash.scmUsernameEnvVar=" + ENV_VAR_SCM_USERNAME);
      scmEnv.put(ENV_VAR_SCM_USERNAME, scmUsername);
    }
    if (scmPassword != null) {
      command.append(" -Dunleash.scmPasswordEnvVar=" + ENV_VAR_SCM_PASSWORD);
      scmEnv.put(ENV_VAR_SCM_PASSWORD, scmPassword);
    }
    if (scmSshPassphrase != null) {
      command.append(" -Dunleash.scmSshPassphraseEnvVar=" + ENV_VAR_SCM_SSH_PASSPHRASE);
      scmEnv.put(ENV_VAR_SCM_SSH_PASSPHRASE, scmSshPassphrase);
    }
    return scmEnv;
  }

  private boolean isReleaseBuild(@SuppressWarnings("rawtypes") AbstractBuild build) {
    return build.getCause(UnleashCause.class) != null;
  }

  public String getGoals() {
    return StringUtils.isBlank(this.goals) ? DescriptorImpl.DEFAULT_GOALS : this.goals;
  }

  public void setGoals(String goals) {
    this.goals = goals;
  }

  public String getProfiles() {
    return this.profiles;
  }

  public void setProfiles(String profiles) {
    this.profiles = profiles;
  }

  public String getReleaseArgs() {
    return this.releaseArgs;
  }

  public void setReleaseArgs(String releaseArgs) {
    this.releaseArgs = releaseArgs;
  }

  public boolean isUseLogTimestamps() {
    return this.useLogTimestamps;
  }

  public void setUseLogTimestamps(boolean useLogTimestamps) {
    this.useLogTimestamps = useLogTimestamps;
  }

  public String getTagNamePattern() {
    return StringUtils.isBlank(this.tagNamePattern) ? DescriptorImpl.DEFAULT_TAG_NAME_PATTERN : this.tagNamePattern;
  }

  public void setTagNamePattern(String tagNamePattern) {
    this.tagNamePattern = tagNamePattern;
  }

  public String getScmMessagePrefix() {
    return this.scmMessagePrefix;
  }

  public void setScmMessagePrefix(String scmMessagePrefix) {
    this.scmMessagePrefix = scmMessagePrefix;
  }

  public boolean isPreselectUseGlobalVersion() {
    return this.preselectUseGlobalVersion;
  }

  public void setPreselectUseGlobalVersion(boolean preselectUseGlobalVersion) {
    this.preselectUseGlobalVersion = preselectUseGlobalVersion;
  }

  @Override
  public Collection<? extends Action> getProjectActions(@SuppressWarnings("rawtypes") AbstractProject job) {
    return Collections.singleton(new UnleashAction((MavenModuleSet) job, this.preselectUseGlobalVersion,
        this.preselectAllowLocalReleaseArtifacts, this.preselectCommitBeforeTagging, false, false));
  }

  public List<HookDescriptor> getHooks() {
    return this.hooks;
  }

  public void setHooks(List<HookDescriptor> hooks) {
    this.hooks = hooks;
  }

  public boolean isPreselectAllowLocalReleaseArtifacts() {
    return this.preselectAllowLocalReleaseArtifacts;
  }

  public void setPreselectAllowLocalReleaseArtifacts(boolean preselectAllowLocalReleaseArtifacts) {
    this.preselectAllowLocalReleaseArtifacts = preselectAllowLocalReleaseArtifacts;
  }

  public boolean isPreselectCommitBeforeTagging() {
    return this.preselectCommitBeforeTagging;
  }

  public void setPreselectCommitBeforeTagging(boolean preselectCommitBeforeTagging) {
    this.preselectCommitBeforeTagging = preselectCommitBeforeTagging;
  }

  public String getWorkflowPath() {
    return this.workflowPath;
  }

  public void setWorkflowPath(String workflowPath) {
    this.workflowPath = workflowPath;
  }

  public String getCredentialsId() {
    return this.credentialsId;
  }

  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  @Extension
  public static class DescriptorImpl extends BuildWrapperDescriptor {
    public static final String DEFAULT_GOALS = "unleash:perform";
    public static final String DEFAULT_PROFILES = "";
    public static final String DEFAULT_RELEASE_ARGS = "";
    public static final boolean DEFAULT_USE_LOG_TIMESTAMPS = true;
    public static final String DEFAULT_TAG_NAME_PATTERN = "@{project.version}";
    public static final String DEFAULT_SCM_MESSAGE_PREFIX = "[unleash-maven-plugin]";
    public static final boolean DEFAULT_PRESELECT_USE_GLOBAL_VERSION = false;
    public static final boolean DEFAULT_PRESELECT_ALLOW_LOCAL_RELEASE_ARTIFACTS = true;
    public static final boolean DEFAULT_PRESELECT_COMMIT_BEFORE_TAGGING = false;
    public static final String DEFAULT_WORKFLOW_PATH = "";

    private static final CredentialsMatcher CREDENTIALS_MATCHER = CredentialsMatchers.anyOf(
        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
        CredentialsMatchers.instanceOf(SSHUserPrivateKey.class));

    private boolean useLogTimestamps = DEFAULT_USE_LOG_TIMESTAMPS;
    private boolean preselectAllowLocalReleaseArtifacts = DEFAULT_PRESELECT_ALLOW_LOCAL_RELEASE_ARTIFACTS;
    private boolean preselectCommitBeforeTagging = DEFAULT_PRESELECT_COMMIT_BEFORE_TAGGING;
    private String tagNamePattern = DEFAULT_TAG_NAME_PATTERN;
    private String scmMessagePrefix = DEFAULT_SCM_MESSAGE_PREFIX;

    public void setUseLogTimestamps(boolean useLogTimestamps) {
      this.useLogTimestamps = useLogTimestamps;
    }

    public boolean isUseLogTimestamps() {
      return this.useLogTimestamps;
    }

    public void setPreselectAllowLocalReleaseArtifacts(boolean preselectAllowLocalReleaseArtifacts) {
      this.preselectAllowLocalReleaseArtifacts = preselectAllowLocalReleaseArtifacts;
    }

    public boolean isPreselectAllowLocalReleaseArtifacts() {
      return this.preselectAllowLocalReleaseArtifacts;
    }

    public void setPreselectCommitBeforeTagging(boolean preselectCommitBeforeTagging) {
      this.preselectCommitBeforeTagging = preselectCommitBeforeTagging;
    }

    public boolean isPreselectCommitBeforeTagging() {
      return this.preselectCommitBeforeTagging;
    }

    public void setTagNamePattern(String tagNamePattern) {
      this.tagNamePattern = tagNamePattern;
    }

    public String getTagNamePattern() {
      return this.tagNamePattern;
    }

    public void setScmMessagePrefix(String scmMessagePrefix) {
      this.scmMessagePrefix = scmMessagePrefix;
    }

    public String getScmMessagePrefix() {
      return this.scmMessagePrefix;
    }

    @Override
    public boolean isApplicable(AbstractProject<?, ?> item) {
      return item instanceof AbstractMavenProject;
    }

    @Override
    public String getDisplayName() {
      return "Unleash";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws hudson.model.Descriptor.FormException {
      req.bindJSON(this, json);
      save();
      return super.configure(req, json);
    }

    // TODO adapt to be able to use SSH credentials also!
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context, @QueryParameter String credentialsId) {
      if (context == null || !context.hasPermission(Item.CONFIGURE)) {
        return new ListBoxModel();
      }
      return new StandardListBoxModel().includeEmptyValue().includeMatchingAs(
          context instanceof hudson.model.Queue.Task ? Tasks.getAuthenticationOf((hudson.model.Queue.Task) context)
              : ACL.SYSTEM,
          context, StandardUsernameCredentials.class, URIRequirementBuilder.create().build(), CREDENTIALS_MATCHER);
    }
  }
}
