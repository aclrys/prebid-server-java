package org.prebid.server.spring.config.bidder;

import org.prebid.server.bidder.BidderDeps;
import org.prebid.server.bidder.nanointeractive.NanointeractiveBidder;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.spring.config.bidder.model.BidderConfigurationProperties;
import org.prebid.server.spring.config.bidder.util.BidderDepsAssembler;
import org.prebid.server.spring.config.bidder.util.UsersyncerCreator;
import org.prebid.server.spring.env.YamlPropertySourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import javax.validation.constraints.NotBlank;

@Configuration
@PropertySource(value = "classpath:/bidder-config/nanointeractive.yaml", factory = YamlPropertySourceFactory.class)
public class NanointeractiveConfiguration {

    private static final String BIDDER_NAME = "nanointeractive";

    @Bean("nanointeractiveConfigurationProperties")
    @ConfigurationProperties("adapters.nanointeractive")
    BidderConfigurationProperties configurationProperties() {
        return new BidderConfigurationProperties();
    }

    @Bean
    BidderDeps nanointeractiveBidderDeps(BidderConfigurationProperties nanointeractiveConfigurationProperties,
                                         @NotBlank @Value("${external-url}") String externalUrl,
                                         JacksonMapper mapper) {

        return BidderDepsAssembler.forBidder(BIDDER_NAME)
                .withConfig(nanointeractiveConfigurationProperties)
                .usersyncerCreator(UsersyncerCreator.create(externalUrl))
                .bidderCreator(config -> new NanointeractiveBidder(config.getEndpoint(), mapper))
                .assemble();
    }

}
