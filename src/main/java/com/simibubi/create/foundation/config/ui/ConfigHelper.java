package com.simibubi.create.foundation.config.ui;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.simibubi.create.Create;
import com.simibubi.create.foundation.utility.Pair;
import com.simibubi.create.infrastructure.config.AllConfigs;

import io.github.fabricators_of_create.porting_lib.config.ConfigTracker;
import io.github.fabricators_of_create.porting_lib.config.ConfigType;
import io.github.fabricators_of_create.porting_lib.config.ModConfig;
import io.github.fabricators_of_create.porting_lib.config.ModConfigSpec;

public class ConfigHelper {

	public static final Pattern unitPattern = Pattern.compile("\\[(in .*)]");
	public static final Pattern annotationPattern = Pattern.compile("\\[@cui:([^:]*)(?::(.*))?]");

	public static final Map<String, ConfigChange> changes = new HashMap<>();
	private static final LoadingCache<String, EnumMap<ConfigType, ModConfig>> configCache =
		CacheBuilder.newBuilder()
			.expireAfterAccess(5, TimeUnit.MINUTES)
			.build(new CacheLoader<String, EnumMap<ConfigType, ModConfig>>() {
				@Override
				public EnumMap<ConfigType, ModConfig> load(@Nonnull String key) {
					return findModConfigsUncached(key);
				}
			});

	// FIXME compat with other config libs?
	private static EnumMap<ConfigType, ModConfig> findModConfigsUncached(String modID) {
		EnumMap<ConfigType, ModConfig> configs = new EnumMap<>(ConfigType.class);
		ConfigTracker.INSTANCE.configSets().forEach((type, modConfigs) -> {
			modConfigs.forEach(modConfig -> {
				if(modConfig.getModId().equals(modID))
					configs.put(type, modConfig);
			});
		});
		return Objects.requireNonNull(configs);
	}

	public static ModConfigSpec findConfigSpecFor(ConfigType type, String modID) {
		if (!modID.equals(Create.ID))
			return configCache.getUnchecked(modID)
				.get(type)
				.getSpec();
		return AllConfigs.byType(type).specification;
	}

	@Nullable
	public static ModConfigSpec findForgeConfigSpecFor(ConfigType type, String modID) {
		ModConfigSpec spec = findConfigSpecFor(type, modID);
		if (spec instanceof ModConfigSpec) {
			return (ModConfigSpec) spec;
		}
		return null;
	}

	public static boolean hasAnyConfig(String modID) {
		if (!modID.equals(Create.ID))
			return !configCache.getUnchecked(modID)
				.isEmpty();
		return true;
	}

	public static boolean hasAnyForgeConfig(String modID) {
		if (!modID.equals(Create.ID))
			return configCache.getUnchecked(modID)
				.values()
				.stream()
				.anyMatch(config -> config.getSpec() instanceof ModConfigSpec);
		return true;
	}

	// Directly set a value
	public static <T> void setConfigValue(ConfigPath path, String value) throws InvalidValueException {
		ModConfigSpec spec = findForgeConfigSpecFor(path.getType(), path.getModID());
		if (spec == null)
			return;

		List<String> pathList = Arrays.asList(path.getPath());
		ModConfigSpec.ValueSpec valueSpec = spec.getSpec().getRaw(pathList);
		ModConfigSpec.ConfigValue<T> configValue = spec.getValues().get(pathList);
		T v = (T) CConfigureConfigPacket.deserialize(configValue.get(), value);
		if (!valueSpec.test(v))
			throw new InvalidValueException();

		configValue.set(v);
	}

	// Add a value to the current UI's changes list
	public static <T> void setValue(String path, ModConfigSpec.ConfigValue<T> configValue, T value,
		@Nullable Map<String, String> annotations) {
		if (value.equals(configValue.get())) {
			changes.remove(path);
		} else {
			changes.put(path, annotations == null ? new ConfigChange(value) : new ConfigChange(value, annotations));
		}
	}

	// Get a value from the current UI's changes list or the config value, if its
	// unchanged
	public static <T> T getValue(String path, ModConfigSpec.ConfigValue<T> configValue) {
		ConfigChange configChange = changes.get(path);
		if (configChange != null)
			// noinspection unchecked
			return (T) configChange.value;
		else
			return configValue.get();
	}

	public static Pair<String, Map<String, String>> readMetadataFromComment(List<String> commentLines) {
		AtomicReference<String> unit = new AtomicReference<>();
		Map<String, String> annotations = new HashMap<>();

		commentLines.removeIf(line -> {
			if (line.trim()
				.isEmpty()) {
				return true;
			}

			Matcher matcher = annotationPattern.matcher(line);
			if (matcher.matches()) {
				String annotation = matcher.group(1);
				String aValue = matcher.group(2);
				annotations.putIfAbsent(annotation, aValue);

				return true;
			}

			matcher = unitPattern.matcher(line);
			if (matcher.matches()) {
				unit.set(matcher.group(1));
			}

			return false;
		});

		return Pair.of(unit.get(), annotations);
	}

	public static class ConfigPath {
		private String modID = Create.ID;
		private ConfigType type = ConfigType.CLIENT;
		private String[] path;

		public static ConfigPath parse(String string) {
			ConfigPath cp = new ConfigPath();
			String p = string;
			int index = string.indexOf(":");
			if (index >= 0) {
				p = string.substring(index + 1);
				if (index >= 1) {
					cp.modID = string.substring(0, index);
				}
			}
			String[] split = p.split("\\.");
			try {
				cp.type = ConfigType.valueOf(split[0].toUpperCase(Locale.ROOT));
			} catch (Exception e) {
				throw new IllegalArgumentException("path must start with either 'client.', 'common.' or 'server.'");
			}

			cp.path = new String[split.length - 1];
			System.arraycopy(split, 1, cp.path, 0, cp.path.length);

			return cp;
		}

		public ConfigPath setID(String modID) {
			this.modID = modID;
			return this;
		}

		public ConfigPath setType(ConfigType type) {
			this.type = type;
			return this;
		}

		public ConfigPath setPath(String[] path) {
			this.path = path;
			return this;
		}

		public String getModID() {
			return modID;
		}

		public ConfigType getType() {
			return type;
		}

		public String[] getPath() {
			return path;
		}
	}

	public static class ConfigChange {
		Object value;
		Map<String, String> annotations;

		ConfigChange(Object value) {
			this.value = value;
		}

		ConfigChange(Object value, Map<String, String> annotations) {
			this(value);
			this.annotations = new HashMap<>();
			this.annotations.putAll(annotations);
		}
	}

	public static class InvalidValueException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
