package pokeraidbot.commands;

import com.jagrosh.jdautilities.commandclient.CommandEvent;
import pokeraidbot.domain.Config;
import pokeraidbot.domain.ConfigRepository;
import pokeraidbot.domain.LocaleService;

public class DonateCommand extends ConfigAwareCommand {
    private static final String link = "https://pledgie.com/campaigns/34823";

    public DonateCommand(LocaleService localeService, ConfigRepository configRepository) {
        super(configRepository);
        this.name = "donate";
        this.help = localeService.getMessageFor(LocaleService.DONATE, LocaleService.DEFAULT);
    }

    @Override
    protected void executeWithConfig(CommandEvent commandEvent, Config config) {
        replyBasedOnConfig(config, commandEvent, link);
    }
}
