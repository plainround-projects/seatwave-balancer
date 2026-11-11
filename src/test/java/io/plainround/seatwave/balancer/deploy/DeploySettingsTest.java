package io.plainround.seatwave.balancer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.balancer.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DeploySettingsTest {

  @Test
  void readsTheDeploySettingsShippedWithTheProject() throws IOException {
    Result<DeploySettings, List<String>> read =
        DeploySettings.from(
            new SettingsReader(SettingsReader.load(Path.of("config/deploy.properties"))));

    assertThat(read).isInstanceOf(Result.Success.class);
  }

  @Test
  void refusesAStopTimeoutDockerWouldRoundToZeroSeconds() {
    Properties properties = valid();
    properties.setProperty("deploy.stopTimeoutMillis", "999");

    Result<DeploySettings, List<String>> read = DeploySettings.from(new SettingsReader(properties));

    assertThat(read)
        .isEqualTo(
            Result.error(
                List.of(
                    "deploy.stopTimeoutMillis must be at least 1000: docker counts whole seconds")));
  }

  @Test
  void reportsAMissingStopTimeoutOnceWithoutTheRuleThatNeedsIt() {
    Properties properties = valid();
    properties.remove("deploy.stopTimeoutMillis");

    Result<DeploySettings, List<String>> read = DeploySettings.from(new SettingsReader(properties));

    assertThat(read).isEqualTo(Result.error(List.of("deploy.stopTimeoutMillis is missing")));
  }

  @Test
  void acceptsAnAuditFileInADeepFolderWithSpaces() {
    Properties properties = valid();
    properties.setProperty(
        "deploy.auditFile", "/checkouts/My Labs/" + "nested-folder/".repeat(20) + "audit.log");

    Result<DeploySettings, List<String>> read = DeploySettings.from(new SettingsReader(properties));

    assertThat(read).isInstanceOf(Result.Success.class);
  }

  private static Properties valid() {
    Properties properties = new Properties();
    properties.setProperty("compose.project", "seatwave-balancer");
    properties.setProperty("deploy.instances", "seatwave-1,seatwave-2");
    properties.setProperty("deploy.backends", "seatwave");
    properties.setProperty("deploy.balancers", "lb1=127.0.0.1:21005");
    properties.setProperty("deploy.drainTimeoutMillis", "1000");
    properties.setProperty("deploy.stopTimeoutMillis", "30000");
    properties.setProperty("deploy.upTimeoutMillis", "1000");
    properties.setProperty("deploy.settleMillis", "0");
    properties.setProperty("deploy.pollMillis", "10");
    properties.setProperty("deploy.auditFile", "build/stack/audit.log");
    return properties;
  }
}
