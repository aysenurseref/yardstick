package nl.tudelft.opencraft.yardstick;

import com.beust.jcommander.DynamicParameter;
import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class Options {

    @Parameter(names = {"--help"}, help = true, description = "Shows help")
    public boolean help;

    @Parameter(names = {"--host", "-h"}, description = "The host of the Minecraft server")
    public String host = "127.0.0.1";

    @Parameter(names = {"--port", "-p"}, description = "The port of the Minecraft server")
    public int port = 25565;

    @Parameter(names = {"--experiment", "-e"}, required = true, description = "The experiment ID")
    public int experiment;

    @Parameter(names = {"--dump-workload", "-d"}, description = "Indicates whether to dump the workload traces to the 'workload' folder")
    public boolean dumpWorkload;

    @Parameter(names = {"--prometheus-host", "-ph"}, description = "The host of the Prometheus server")
    public String prometheusHost;

    @Parameter(names = {"--prometheus-port", "-pp"}, description = "The port of the Prometheus server")
    public int prometheusPort = 9091;

    @Parameter(names = {"--start", "-s"}, converter = DateConverter.class, description = "The start time of the experiment - HH:mm[:ss]")
    public LocalTime start;

    @DynamicParameter(names = "-E", description = "The experiment parameters. Differs per experiment")
    public Map<String, String> experimentParams = new HashMap<>();

    public static class DateConverter implements IStringConverter<LocalTime> {

        public static DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_TIME;
        //public static DateFormat FORMAT = new SimpleDateFormat("HH:mm:ss");

        @Override
        public LocalTime convert(String string) {
            return LocalTime.from(FORMATTER.parse(string));
        }

    }

}
