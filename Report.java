///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//REPOS mavencentral,jboss=https://repository.jboss.org/nexus/content/repositories/releases/
//DEPS org.jboss.set.dependency-alignment:core:1.0.5
//DEPS io.quarkus.qute:qute-core:3.40.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.12.7.1
// Override maven-model 3.5.4 used by the updater, which cannot parse child.project.url.inherit.append.path (e.g. in wildfly-clustering)
// https://github.com/jboss-set/maven-dependency-updater/issues/20
//DEPS org.apache.maven:maven-model:3.6.3
//FILES logback.xml

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import io.quarkus.qute.HtmlEscaper;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.TemplateLocator.TemplateLocation;
import io.quarkus.qute.Variant;
import org.jboss.set.mavendependencyupdater.ArtifactResult;
import org.jboss.set.mavendependencyupdater.ComponentUpgrade;
import org.jboss.set.mavendependencyupdater.DefaultAvailableVersionsResolver;
import org.jboss.set.mavendependencyupdater.DependencyEvaluator;
import org.jboss.set.mavendependencyupdater.LocatedDependency;
import org.jboss.set.mavendependencyupdater.LocatedProperty;
import org.jboss.set.mavendependencyupdater.PomDependencyUpdater;
import org.jboss.set.mavendependencyupdater.configuration.Configuration;
import org.jboss.set.mavendependencyupdater.core.aggregation.ComponentUpgradeAggregator;
import org.jboss.set.mavendependencyupdater.projectparser.PmeDependencyCollector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates dependency upgrade reports for the projects listed in config-projects.json into target/site.
 *
 * @author Radoslav Husar
 */
public class Report {

    /**
     * A project to report on; {@code branch} is optional and defaults to the repository's default branch.
     */
    public record Project(String name, String scm, String branch) {
        /**
         * Web URL of the repository derived from the SCM URL, e.g. {@code git@github.com:wildfly/wildfly.git} becomes
         * {@code https://github.com/wildfly/wildfly}.
         */
        public String webUrl() {
            String url = scm.replaceFirst("\\.git$", "");
            if (url.startsWith("git@")) {
                url = "https://" + url.substring("git@".length()).replaceFirst(":", "/");
            }
            return url;
        }

        /**
         * Unique identifier used for the checkout directory and the report file name, e.g. {@code wildfly-41.x}.
         */
        public String id() {
            return (branch == null) ? name : name + "-" + branch.replace('/', '-');
        }
    }

    public record Repository(String name, String url, String color) {
    }

    public record Upgrade(String version, Repository repository) {
    }

    public record Component(String gav, Upgrade micro, Upgrade minor, Upgrade latest) {
    }

    public record ProjectReport(Project project, String branch, String commit,List<Repository> repositories, List<Component> components) {
        public String shortCommit() {
            return commit.substring(0, 12);
        }
    }

    // Repository badge colors, assigned in the order repositories are configured
    static final List<String> COLORS = List.of("#a8df65", "#edf492", "#efb960", "#ee91bc");

    static final Path TARGET = Path.of("target");
    static final Path SITE = TARGET.resolve("site");
    static final Path TEMPLATES = Path.of("templates");

    public static void main(String[] args) throws Exception {
        Files.createDirectories(SITE);
        Configuration configuration = new Configuration(new File("config-dependency-updater.json"));
        Engine engine = createEngine();
        String generated = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));

        List<Repository> repositories = new ArrayList<>();
        for (Map.Entry<String, String> entry : configuration.getRepositories().entrySet()) {
            repositories.add(new Repository(entry.getKey(), entry.getValue(), COLORS.get(repositories.size() % COLORS.size())));
        }

        List<ProjectReport> reports = new ArrayList<>();
        List<Project> projects = new ObjectMapper().readValue(new File("config-projects.json"), new TypeReference<>() {});
        for (Project project : projects) {
            ProjectReport report = generate(project, configuration, repositories);
            Files.writeString(SITE.resolve(project.id() + ".html"), engine.getTemplate("report")
                    .data("report", report)
                    .data("generated", generated)
                    .render());
            System.out.printf("Report for %s: %d components with possible upgrades%n", project.id(), report.components().size());
            reports.add(report);
        }

        Files.writeString(SITE.resolve("index.html"), engine.getTemplate("index")
                .data("reports", reports)
                .data("generated", generated)
                .render());
    }

    static ProjectReport generate(Project project, Configuration configuration, List<Repository> repositories) throws Exception {
        // The collector parses the whole module tree starting from the root POM, so a (shallow) checkout is required
        Path checkout = TARGET.resolve(project.id());
        delete(checkout);
        List<String> clone = new ArrayList<>(List.of("clone", "--quiet", "--depth", "1"));
        if (project.branch() != null) {
            clone.addAll(List.of("--branch", project.branch()));
        }
        clone.addAll(List.of(project.scm(), checkout.toString()));
        git(clone.toArray(String[]::new));
        String branch = git("-C", checkout.toString(), "rev-parse", "--abbrev-ref", "HEAD");
        String commit = git("-C", checkout.toString(), "rev-parse", "HEAD");
        System.out.printf("Generating report for %s (%s) at %s%n", project.name(), branch, commit);

        File pom = checkout.resolve("pom.xml").toFile();
        PmeDependencyCollector collector = new PmeDependencyCollector(pom);
        DependencyEvaluator evaluator = new DependencyEvaluator(configuration, new DefaultAvailableVersionsResolver(configuration), null);
        List<ArtifactResult<ComponentUpgrade>> upgrades = evaluator.getVersionsToUpgrade(collector.getDependenciesPerProjects()).stream()
                .filter(ArtifactResult::anyPresent)
                .sorted(Comparator.comparing(upgrade -> upgrade.getArtifactRef().toString()))
                .toList();

        List<Component> components = aggregate(pom, upgrades).stream()
                .map(upgrade -> new Component(upgrade.getArtifactRef().getGroupId() + ":" + upgrade.getArtifactRef().getArtifactId() + ":" + upgrade.getArtifactRef().getVersionString(),
                        toUpgrade(upgrade.getLatestConfigured(), repositories),
                        toUpgrade(upgrade.getLatestMinor(), repositories),
                        toUpgrade(upgrade.getVeryLatest(), repositories)))
                .toList();

        return new ProjectReport(project, branch, commit, repositories, components);
    }

    /**
     * Same as {@link ComponentUpgradeAggregator#aggregateComponentUpgrades(File, List)} but keeps every upgrade of
     * a dependency with a literal version; the original collapses all of them into the first one since their located
     * property is {@code null}.
     * https://github.com/jboss-set/maven-dependency-updater/issues/21
     */
    static List<ArtifactResult<ComponentUpgrade>> aggregate(File rootPom, List<ArtifactResult<ComponentUpgrade>> upgrades) throws Exception {
        Set<LocatedProperty> modifiedProperties = new HashSet<>();
        List<ArtifactResult<ComponentUpgrade>> aggregated = new ArrayList<>();
        for (ArtifactResult<ComponentUpgrade> upgrade : upgrades) {
            ComponentUpgrade any = upgrade.getAny().orElseThrow();
            Optional<LocatedDependency> located = (any.getProject() != null)
                    ? PomDependencyUpdater.locateDependency(any.getProject(), upgrade.getArtifactRef())
                    : PomDependencyUpdater.locateDependency(rootPom, upgrade.getArtifactRef());
            if (located.isPresent()) {
                LocatedProperty property = located.get().getLocatedProperty();
                if (property == null || modifiedProperties.add(property)) {
                    aggregated.add(upgrade);
                }
            }
        }
        return aggregated;
    }

    static Upgrade toUpgrade(Optional<ComponentUpgrade> upgrade, List<Repository> repositories) {
        return upgrade.map(u -> new Upgrade(u.getNewVersion(), repositories.stream()
                        .filter(repository -> repository.name().equals(u.getRepository()))
                        .findFirst()
                        .orElse(new Repository(u.getRepository(), "", "#ddd"))))
                .orElse(null);
    }

    static Engine createEngine() {
        Variant html = Variant.forContentType(Variant.TEXT_HTML);
        return Engine.builder()
                .addDefaults()
                .addValueResolver(new ReflectionValueResolver())
                .addResultMapper(new HtmlEscaper(List.of(Variant.TEXT_HTML)))
                .addLocator(id -> {
                    Path path = TEMPLATES.resolve(id + ".html");
                    if (!Files.exists(path)) {
                        return Optional.empty();
                    }
                    return Optional.of(new TemplateLocation() {
                        @Override
                        public java.io.Reader read() {
                            try {
                                return Files.newBufferedReader(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public Optional<Variant> getVariant() {
                            return Optional.of(html);
                        }
                    });
                })
                .build();
    }

    static String git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command));
        }
        return output;
    }

    static void delete(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var paths = Files.walk(path)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
