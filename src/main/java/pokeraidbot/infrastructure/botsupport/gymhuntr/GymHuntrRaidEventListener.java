package pokeraidbot.infrastructure.botsupport.gymhuntr;

import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.hooks.EventListener;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pokeraidbot.BotService;
import pokeraidbot.Utils;
import pokeraidbot.commands.NewRaidGroupCommand;
import pokeraidbot.domain.config.ClockService;
import pokeraidbot.domain.config.LocaleService;
import pokeraidbot.domain.gym.Gym;
import pokeraidbot.domain.gym.GymRepository;
import pokeraidbot.domain.pokemon.Pokemon;
import pokeraidbot.domain.pokemon.PokemonRepository;
import pokeraidbot.domain.raid.Raid;
import pokeraidbot.domain.raid.RaidRepository;
import pokeraidbot.infrastructure.jpa.config.Config;
import pokeraidbot.infrastructure.jpa.config.ServerConfigRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ExecutorService;

import static pokeraidbot.Utils.getStartOfRaid;
import static pokeraidbot.Utils.printTime;

public class GymHuntrRaidEventListener implements EventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(GymHuntrRaidEventListener.class);

    private ServerConfigRepository serverConfigRepository;
    private RaidRepository raidRepository;
    private GymRepository gymRepository;
    private PokemonRepository pokemonRepository;
    private LocaleService localeService;
    private ExecutorService executorService;
    private final ClockService clockService;
    private final BotService botService;

    public GymHuntrRaidEventListener(ServerConfigRepository serverConfigRepository, RaidRepository raidRepository,
                                     GymRepository gymRepository, PokemonRepository pokemonRepository,
                                     LocaleService localeService, ExecutorService executorService,
                                     ClockService clockService, BotService botService) {
        this.serverConfigRepository = serverConfigRepository;
        this.raidRepository = raidRepository;
        this.gymRepository = gymRepository;
        this.pokemonRepository = pokemonRepository;
        this.localeService = localeService;
        this.executorService = executorService;
        this.clockService = clockService;
        this.botService = botService;
    }

    @Override
    public void onEvent(Event event) {
        if (event instanceof GuildMessageReceivedEvent) {
            GuildMessageReceivedEvent guildEvent = (GuildMessageReceivedEvent) event;
            final User messageAuthor = guildEvent.getAuthor();
            if (isUserGymhuntrBot(messageAuthor) || isUserPokeAlarmBot(messageAuthor)) {
                final String serverName = guildEvent.getGuild().getName().toLowerCase();
                final List<MessageEmbed> embeds = guildEvent.getMessage().getEmbeds();
                if (embeds != null && embeds.size() > 0) {
                    for (MessageEmbed embed : embeds) {
                        final LocalDateTime currentDateTime = clockService.getCurrentDateTime();
                        final String description = embed.getDescription();
                        final String title = embed.getTitle();
                        List<String> newRaidArguments;
                        if (isUserGymhuntrBot(messageAuthor)) {
                            newRaidArguments = gymhuntrArgumentsToCreateRaid(title, description, clockService);
                        } else if (isUserPokeAlarmBot(messageAuthor)) {
                            newRaidArguments = pokeAlarmArgumentsToCreateRaid(title, description, clockService);
                        } else {
                            newRaidArguments = new ArrayList<>();
                        }
                        if (newRaidArguments != null && newRaidArguments.size() > 0) {
                            // todo: arguments checking
                            final Iterator<String> iterator = newRaidArguments.iterator();
                            final String gym = iterator.next();
                            final String pokemon = iterator.next();
                            final String time = iterator.next();
                            final Pokemon raidBoss = pokemonRepository.getByName(pokemon);
                            final Config config = serverConfigRepository.getConfigForServer(serverName);
                            final String region = config.getRegion();
                            final Gym raidGym = gymRepository.findByName(gym, region);
                            final LocalDate currentDate = currentDateTime.toLocalDate();
                            final LocalDateTime endOfRaid = LocalDateTime.of(currentDate,
                                    LocalTime.parse(time, Utils.timeParseFormatter));
                            handleRaidFromIntegration(botService.getBot().getSelfUser(),
                                    guildEvent, raidBoss, raidGym, endOfRaid, config, clockService);
                        }
                    }
                }
            }
        }
    }

    public void handleRaidFromIntegration(User user, GuildMessageReceivedEvent guildEvent, Pokemon raidBoss, Gym raidGym,
                                          LocalDateTime endOfRaid, Config config, ClockService clockService) {
        final LocalDateTime now = clockService.getCurrentDateTime();
        LocalDateTime currentDateTime = now;
        final boolean moreThan10MinutesLeftOnRaid = endOfRaid.isAfter(currentDateTime.plusMinutes(10));
        if (moreThan10MinutesLeftOnRaid) {
            final Raid raidToCreate = new Raid(raidBoss,
                    endOfRaid,
                    raidGym,
                    localeService, config.getRegion());
            final Raid createdRaid;
            try {
                createdRaid = raidRepository.newRaid(user, raidToCreate);
                final Locale locale = config.getLocale();
                // todo: fetch from config what channel to post this message in
                final MessageChannel channel = guildEvent.getMessage().getChannel();
                EmbedBuilder embedBuilder = new EmbedBuilder().setTitle(null, null);
                StringBuilder sb = new StringBuilder();
                sb.append(localeService.getMessageFor(LocaleService.NEW_RAID_CREATED,
                        locale, createdRaid.toString(locale)));
                LocalTime groupStart = null;
                // todo: fetch setting 10 minutes from server config?
                final LocalDateTime startOfRaid = getStartOfRaid(createdRaid.getEndOfRaid(), createdRaid.isExRaid());
                if (now.isBefore(startOfRaid)) {
                    groupStart = startOfRaid.toLocalTime().plusMinutes(10);
                } else if (now.isAfter(startOfRaid) && now.plusMinutes(15).isBefore(createdRaid.getEndOfRaid())) {
                    groupStart = now.toLocalTime().plusMinutes(10);
                }

                if (groupStart != null) {
                    NewRaidGroupCommand.createRaidGroup(channel, config, user,
                            config.getLocale(), groupStart, createdRaid.getId(), localeService, raidRepository,
                            botService, serverConfigRepository, pokemonRepository, gymRepository,
                            clockService, executorService);
                }
                embedBuilder.setDescription(sb.toString());
                final MessageEmbed messageEmbed = embedBuilder.build();
                channel.sendMessage(messageEmbed).queue(m -> {
                    LOGGER.info("Raid created via Bot integration: " + createdRaid);
                });
            } catch (Throwable t) {
                LOGGER.warn("Exception when trying to create raid via botintegration: " +
                        t.getMessage());
            }
        } else {
            LOGGER.debug("Skipped creating raid at " + raidGym +
                    ", less than 10 minutes remaining on it.");
        }
    }

    public static boolean isUserPokeAlarmBot(User user) {
        return user.isBot() && (user.getName().equalsIgnoreCase("raid") ||
                user.getName().equalsIgnoreCase("egg"));
    }

    public static boolean isUserGymhuntrBot(User user) {
        return user.isBot() && StringUtils.containsIgnoreCase(
                user.getName(), "gymhuntrbot");
    }

    public static List<String> pokeAlarmArgumentsToCreateRaid(String title, String description,
                                                              ClockService clockService) {
        String gym, pokemon, timeString;
        if (title.contains("raid is available against")) {
            final String[] titleSplit = title.replaceAll("!", "").split(" ");
            pokemon = titleSplit[titleSplit.length - 1];
            final String[] descriptionSplit = description.split(" ");
            timeString = printTime(LocalTime.parse(descriptionSplit[descriptionSplit.length - 3]));
            final String[] gymSplit = title.split("raid is available against");
            gym = gymSplit[0].trim();
        } else if (title.contains("has a level 5") && description.contains("will hatch")) {
            // todo: fetch seasonal pokemon for region from some repo?
            pokemon = "Raikou";
            final String[] descriptionSplit = description.split(" ");
            timeString = printTime(LocalTime.parse(descriptionSplit[descriptionSplit.length - 3])
                    .plusMinutes(Utils.RAID_DURATION_IN_MINUTES));
            gym = title.split("has a level 5")[0].trim();
        } else {
            return new ArrayList<>(); // We shouldn't create a raid for this case, non-tier 5 egg
        }
        return Arrays.asList(new String[]{gym, pokemon, timeString});
    }

    public static List<String> gymhuntrArgumentsToCreateRaid(String title, String description,
                                                             ClockService clockService) {
        String gym, pokemon, timeString;
        if (title.contains("Raid has started!")) {
            final String[] firstPass = description.replaceAll("[*]", "").replaceAll("[.]", "")
                    .replaceAll("Raid Ending: ", "").split("\n");
            final String[] timeArguments = firstPass[3].replaceAll("hours ", "")
                    .replaceAll("min ", "").replaceAll("sec", "").split(" ");
            timeString = printTime(clockService.getCurrentTime()
                    .plusHours(Long.parseLong(timeArguments[0]))
                    .plusMinutes(Long.parseLong(timeArguments[1]))
                    .plusSeconds(Long.parseLong(timeArguments[2])));
            gym = firstPass[0].trim();
            pokemon = firstPass[1].trim();
        } else if (title.contains("Level 5 Raid is starting soon!")) {
            final String[] firstPass = description.replaceAll("[*]", "").replaceAll("[.]", "")
                    .replaceAll("Raid Starting: ", "").split("\n");
            pokemon = "Raikou"; // todo: fetch from some repo keeping track of what tier 5 boss is active for the region?
            gym = firstPass[0].trim();
            final String[] timeArguments = firstPass[1].replaceAll("hours ", "")
                    .replaceAll("min ", "").replaceAll("sec", "").split(" ");
            timeString = printTime(clockService.getCurrentTime()
                    .plusHours(Long.parseLong(timeArguments[0]))
                    .plusMinutes(Long.parseLong(timeArguments[1]))
                    .plusSeconds(Long.parseLong(timeArguments[2]))
                    .plusMinutes(Utils.RAID_DURATION_IN_MINUTES));
        } else {
            return new ArrayList<>(); // = We shouldn't create this raid, since it is a non-tier 5 egg
        }
        final String[] argumentsInOrder = new String[]{gym, pokemon, timeString};
        return Arrays.asList(argumentsInOrder);
    }
}
