package io.github.arethore.ha_auto_rtl433.conf;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration tree populated from {@code config.yaml}. It describes MQTT
 * connectivity, rtl_433 processing, and logging preferences that the
 * application consumes at startup.
 */
public class Config {
    private Logback logback = new Logback();
    private Mqtt mqtt;
    private Rtl433 rtl433;

    public Logback getLogback() {
        return logback;
    }

    public void setLogback(Logback logback) {
        this.logback = logback != null ? logback : new Logback();
    }

    public Mqtt getMqtt() {
        return mqtt;
    }

    public void setMqtt(Mqtt mqtt) {
        this.mqtt = mqtt;
    }

    public Rtl433 getRtl433() {
        return rtl433;
    }

    public void setRtl433(Rtl433 rtl433) {
        this.rtl433 = rtl433;
    }

    /**
     * Logging configuration mapping controlling the Logback runtime behaviour.
     */
    public static class Logback {
        private String level = "info";
        private String format = "text";
        private boolean timestamp = true;
        private boolean color = true;
        private String file;
        private String rollingPattern;
        private Integer maxHistory;
        private String maxFileSize;

        public String getLevel() {
            return level;
        }

        public void setLevel(String level) {
            this.level = level;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public boolean isTimestamp() {
            return timestamp;
        }

        public void setTimestamp(boolean timestamp) {
            this.timestamp = timestamp;
        }

        public boolean isColor() {
            return color;
        }

        public void setColor(boolean color) {
            this.color = color;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }

        public String getRollingPattern() {
            return rollingPattern;
        }

        public void setRollingPattern(String rollingPattern) {
            this.rollingPattern = rollingPattern;
        }

        public Integer getMaxHistory() {
            return maxHistory;
        }

        public void setMaxHistory(Integer maxHistory) {
            this.maxHistory = maxHistory;
        }

        public String getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(String maxFileSize) {
            this.maxFileSize = maxFileSize;
        }
    }

    /**
     * MQTT connection and behaviour settings used by both the data publisher
     * and the availability monitor.
     */
    public static class Mqtt {
        private String host;
        private int port;
        private String username;
        private String password;
        private boolean reconfigExisting;
        private Integer queueCapacity;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isReconfigExisting() {
            return reconfigExisting;
        }

        public void setReconfigExisting(boolean reconfigExisting) {
            this.reconfigExisting = reconfigExisting;
        }

        public Integer getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(Integer queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /**
     * rtl_433 related options including the command to execute and the device
     * whitelist that drives entity generation.
     */
    public static class Rtl433 {
        private String process;
        private List<WhitelistEntry> whitelist;

        public String getProcess() {
            return process;
        }

        public void setProcess(String process) {
            this.process = process;
        }

        public List<WhitelistEntry> getWhitelist() {
            return whitelist;
        }

        public void setWhitelist(List<WhitelistEntry> whitelist) {
            this.whitelist = whitelist;
        }

        /**
         * Single rtl_433 device definition that enumerates the entities to expose
         * for a given {@code model}/{@code id} pair.
         */
        public static class WhitelistEntry {
            private String model;
            private String id;
            private String name;
            private List<Entity> entities;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }

            public List<Entity> getEntities() {
                return entities;
            }

            public void setEntities(List<Entity> entities) {
                this.entities = entities;
            }

            public List<Entity> getAllEntities() {
                return entities != null ? entities : new ArrayList<>();
            }

            /**
             * Describes a Home Assistant entity sourced from a rtl_433 field and its
             * optional metadata or transformations.
             */
            public static class Entity {
                private String attribute;
                private String name;
                private String type;
                private String entityClass;
                private String unit;
                private String rename;
                private String valueTemplate;
                private String conversion;
                private Object onValue;
                private Object offValue;

                public String getAttribute() {
                    return attribute;
                }

                public void setAttribute(String attribute) {
                    this.attribute = attribute;
                }

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }

                public String getType() {
                    return type;
                }

                public void setType(String type) {
                    this.type = type;
                }

                public String getEntityClass() {
                    return entityClass;
                }

                public void setEntityClass(String entityClass) {
                    this.entityClass = entityClass;
                }

                public String getUnit() {
                    return unit;
                }

                public void setUnit(String unit) {
                    this.unit = unit;
                }

                public String getRename() {
                    return rename;
                }

                public void setRename(String rename) {
                    this.rename = rename;
                }

                public String getValueTemplate() {
                    return valueTemplate;
                }

                public void setValueTemplate(String valueTemplate) {
                    this.valueTemplate = valueTemplate;
                }

                public String getConversion() {
                    return conversion;
                }

                public void setConversion(String conversion) {
                    this.conversion = conversion;
                }

                public Object getOnValue() {
                    return onValue;
                }

                public void setOnValue(Object onValue) {
                    this.onValue = onValue;
                }

                public Object getOffValue() {
                    return offValue;
                }

                public void setOffValue(Object offValue) {
                    this.offValue = offValue;
                }
            }
        }
    }
}
