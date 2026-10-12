package com.saipramod.actiongraph.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saipramod.actiongraph.extract.Extractor;
import com.saipramod.actiongraph.extract.LlmExtractor;
import com.saipramod.actiongraph.extract.RuleBasedExtractor;
import com.saipramod.actiongraph.service.MeetingService;
import com.saipramod.actiongraph.stt.SpeechToText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDate;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    Extractor extractor(ObjectProvider<ChatModel> chatModel) {
        ChatModel model = chatModel.getIfAvailable();
        return model == null ? new RuleBasedExtractor() : new LlmExtractor(ChatClient.create(model));
    }

    @Bean
    MeetingService meetingService(Extractor extractor) {
        return new MeetingService(extractor);
    }

    @Bean
    SpeechToText speechToText() {
        return new SpeechToText.NotConfigured();
    }

    /** Loads the three example meetings so the dashboard has something to show. */
    @Bean
    ApplicationRunner seedDemo(MeetingService service, @Value("${actiongraph.seed-demo:true}") boolean seed) {
        return args -> {
            if (!seed) {
                return;
            }
            JsonNode root = new ObjectMapper().readTree(new ClassPathResource("eval/hard-set.json").getInputStream());
            for (JsonNode m : root.path("meetings")) {
                var meeting = service.ingest(m.get("title").asText(), LocalDate.parse(m.get("date").asText()),
                        m.get("series").asText(), m.get("transcript").asText());
                log.info("Seeded {} '{}': {} items, {} decisions, {} carried over, {} completed",
                        meeting.id(), meeting.title(), meeting.itemIds().size(), meeting.decisions().size(),
                        meeting.carriedOver().size(), meeting.completed().size());
            }
        };
    }
}
