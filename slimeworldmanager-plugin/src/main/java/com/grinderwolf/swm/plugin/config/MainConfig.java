package com.grinderwolf.swm.plugin.config;

import com.google.common.reflect.TypeToken;
import com.grinderwolf.swm.plugin.log.Logging;
import lombok.Data;
import lombok.Getter;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

import java.io.IOException;

@Data
@ConfigSerializable
public class MainConfig {

    @Setting(value = "enable_async_world_gen")
    private boolean asyncWorldGenerate = true;

    @Setting(value = "enable_commands")
    private boolean enableCommands = true;

    @Setting("updater")
    private UpdaterOptions updaterOptions = new UpdaterOptions();

    @Getter
    @ConfigSerializable
    public static class UpdaterOptions {

        @Setting(value = "enabled")
        private boolean enabled = false;

        @Setting(value = "onjoinmessage")
        private boolean messageEnabled = false;
    }

    public void save() {
        try {
            ConfigManager.getMainConfigLoader().save(ConfigManager.getMainConfigLoader().createEmptyNode().setValue(TypeToken.of(MainConfig.class), this));
        } catch (IOException | ObjectMappingException ex) {
            Logging.error("Failed to save worlds config file:");
            ex.printStackTrace();
        }
    }
}
