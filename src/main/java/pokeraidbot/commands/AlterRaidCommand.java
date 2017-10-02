package pokeraidbot.commands;

import com.jagrosh.jdautilities.commandclient.CommandEvent;
import com.jagrosh.jdautilities.commandclient.CommandListener;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.utils.PermissionUtil;
import pokeraidbot.Utils;
import pokeraidbot.domain.config.LocaleService;
import pokeraidbot.domain.errors.UserMessedUpException;
import pokeraidbot.domain.gym.Gym;
import pokeraidbot.domain.gym.GymRepository;
import pokeraidbot.domain.pokemon.Pokemon;
import pokeraidbot.domain.pokemon.PokemonRepository;
import pokeraidbot.domain.raid.Raid;
import pokeraidbot.domain.raid.RaidRepository;
import pokeraidbot.infrastructure.jpa.config.Config;
import pokeraidbot.infrastructure.jpa.config.ConfigRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static pokeraidbot.Utils.*;

/**
 * !raid change when [New time (HH:MM)] [Pokestop name] (Only administrators or raid creator)
 * !raid change pokemon [Pokemon] [Pokestop name] (Only administrators or raid creator)
 * !raid change remove [Pokestop name] (Only administrators)
 */
public class AlterRaidCommand extends ConfigAwareCommand {
    private final GymRepository gymRepository;
    private final RaidRepository raidRepository;
    private final PokemonRepository pokemonRepository;
    private final LocaleService localeService;

    public AlterRaidCommand(GymRepository gymRepository, RaidRepository raidRepository,
                            PokemonRepository pokemonRepository, LocaleService localeService,
                            ConfigRepository configRepository,
                            CommandListener commandListener) {
        super(configRepository, commandListener);
        this.pokemonRepository = pokemonRepository;
        this.localeService = localeService;
        this.name = "change";
        // todo: i18n
        this.help = " Ändra något som blev fel vid skapandet av en raid. Skriv \"!raid man change\" för detaljer.";
        //localeService.getMessageFor(LocaleService.NEW_RAID_HELP, LocaleService.DEFAULT);
        this.gymRepository = gymRepository;
        this.raidRepository = raidRepository;
    }

    @Override
    protected void executeWithConfig(CommandEvent commandEvent, Config config) {
        final String userName = commandEvent.getAuthor().getName();
        final String[] args = commandEvent.getArgs().split(" ");
        String whatToChange = args[0].trim().toLowerCase();
        String whatToChangeTo = args[1].trim().toLowerCase();
        StringBuilder gymNameBuilder = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            gymNameBuilder.append(args[i]).append(" ");
        }
        String gymName = gymNameBuilder.toString().trim();
        final Gym gym = gymRepository.search(userName, gymName, config.getRegion());
        Raid raid = raidRepository.getActiveRaidOrFallbackToExRaid(gym, config.getRegion());
        verifyPermission(commandEvent, userName, raid);
        switch (whatToChange) {
            case "when":
                LocalTime endsAtTime = LocalTime.parse(whatToChangeTo, Utils.timeParseFormatter);
                LocalDateTime endsAt = LocalDateTime.of(LocalDate.now(), endsAtTime);

                assertTimeNotInNoRaidTimespan(userName, endsAtTime, localeService);
                assertTimeNotMoreThanXHoursFromNow(userName, endsAtTime, localeService, 2);
                assertCreateRaidTimeNotBeforeNow(userName, endsAt, localeService);
                raid = raidRepository.changeEndOfRaid(raid, endsAt);
                break;
            case "pokemon":
                final Pokemon pokemon = pokemonRepository.getByName(whatToChangeTo);
                raid = raidRepository.changePokemon(raid, pokemon);
                break;
            case "remove":
                final boolean userIsNotAdministrator = !PermissionUtil.checkPermission(commandEvent.getTextChannel(),
                        commandEvent.getMember(), Permission.ADMINISTRATOR);
                if (userIsNotAdministrator) {
                    // todo: i18n
                    throw new UserMessedUpException(userName, "Only administrators can delete raids, sorry.");
                }
                // todo: got "Zhorhn: Kunde inte hitta ett unikt gym/pokestop, din sökning returnerade mer än 5 resultat. Försök vara mer precis."
                // while trying to delete. Check what that was.
                if (raidRepository.delete(raid)) {
                    raid = null;
                } else {
                    throw new UserMessedUpException(userName,
                            "Could not delete raid since you tried to delete one that doesn't exist.");
                }
                break;
            default:
                // todo: i18n
                throw new UserMessedUpException(userName, "Bad syntax of command. Refer to command help: !raid help");
        }
        // todo: i18n
        if (raid != null) {
            replyBasedOnConfig(config, commandEvent, "Corrected raid: " + raid.toString());
        } else {
            replyBasedOnConfig(config, commandEvent, "Deleted raid.");
        }
        //localeService.getMessageFor(LocaleService.NEW_RAID_CREATED,
//                localeService.getLocaleForUser(userName), raid.toString()));
    }

    private void verifyPermission(CommandEvent commandEvent, String userName, Raid raid) {
        final boolean userIsNotAdministrator = !PermissionUtil.checkPermission(commandEvent.getTextChannel(),
                commandEvent.getMember(), Permission.ADMINISTRATOR);
        final boolean userIsNotRaidCreator = !userName.equalsIgnoreCase(raid.getCreator());
        if (userIsNotAdministrator && userIsNotRaidCreator) {
            // todo: i18n
            throw new UserMessedUpException(userName, "You are not the creator of this raid, nor an administrator!");
        }
    }
}