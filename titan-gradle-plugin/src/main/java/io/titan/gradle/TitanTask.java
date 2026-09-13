package io.titan.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Legacy lifecycle diagnostic task has no reusable output")
public abstract class TitanTask extends DefaultTask {

    @Input
    private String phase;

    @TaskAction
    public void run() {
        getLogger().lifecycle("Titan skeleton task '{}' executed for phase '{}'", getName(), phase);
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }
}
