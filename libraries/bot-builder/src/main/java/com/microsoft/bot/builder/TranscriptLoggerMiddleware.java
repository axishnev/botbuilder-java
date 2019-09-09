package com.microsoft.bot.builder;

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.microsoft.bot.schema.Activity;
import com.microsoft.bot.schema.ActivityTypes;
import com.microsoft.bot.schema.ResourceResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * When added, this middleware will log incoming and outgoing activitites to a ITranscriptStore.
 */
public class TranscriptLoggerMiddleware implements Middleware {
    // https://github.com/FasterXML/jackson-databind/wiki/Serialization-Features
    private static ObjectMapper mapper;

    static {
        mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        mapper.findAndRegisterModules();
    }

    private TranscriptLogger transcriptLogger;
    private static final Logger logger = LoggerFactory.getLogger(TranscriptLoggerMiddleware.class);

    private Queue<Activity> transcript = new ConcurrentLinkedQueue<Activity>();

    /**
     * Initializes a new instance of the <see cref="TranscriptLoggerMiddleware"/> class.
     *
     * @param transcriptLogger The transcript logger to use.
     */
    public TranscriptLoggerMiddleware(TranscriptLogger transcriptLogger) {
        if (transcriptLogger == null)
            throw new NullPointerException("TranscriptLoggerMiddleware requires a ITranscriptLogger implementation.  ");

        this.transcriptLogger = transcriptLogger;

    }

    /**
     * initialization for middleware turn.
     *
     * @param context
     * @param next
     * @return
     */
    @Override
    public CompletableFuture<Void> onTurnAsync(TurnContext context, NextDelegate next) {
        // log incoming activity at beginning of turn
        if (context.getActivity() != null) {
            JsonNode role = null;
            if (context.getActivity().getFrom() == null) {
                throw new RuntimeException("Activity does not contain From field");
            }
            if (context.getActivity().getFrom().getProperties().containsKey("role")) {
                role = context.getActivity().getFrom().getProperties().get("role");
            }

            if (role == null || StringUtils.isBlank(role.asText())) {
                context.getActivity().getFrom().getProperties().put("role", mapper.createObjectNode().with("user"));
            }
            Activity activityTemp = Activity.clone(context.getActivity());

            LogActivity(Activity.clone(context.getActivity()));
        }

        // hook up onSend pipeline
        context.onSendActivities((ctx, activities, nextSend) -> {
            // run full pipeline
            CompletableFuture<ResourceResponse[]> responses = null;

            if (nextSend != null) {
                responses = nextSend.get();
            }

            for (Activity activity : activities) {
                LogActivity(Activity.clone(activity));
            }

            return responses;
        });

        // hook up update activity pipeline
        context.onUpdateActivity((ctx, activity, nextUpdate) -> {
            // run full pipeline
            CompletableFuture<ResourceResponse> response = null;

            if (nextUpdate != null) {
                response = nextUpdate.get();
            }

            // add Message Update activity
            Activity updateActivity = Activity.clone(activity);
            updateActivity.setType(ActivityTypes.MESSAGE_UPDATE);
            LogActivity(updateActivity);

            return response;
        });

        // hook up delete activity pipeline
        context.onDeleteActivity((ctx, reference, nextDel) -> {
            // run full pipeline

            if (nextDel != null) {
                logger.debug(String.format("Transcript logActivity next delegate: %s)", nextDel));
                nextDel.get();
            }

            // add MessageDelete activity
            // log as MessageDelete activity
            Activity deleteActivity = new Activity(ActivityTypes.MESSAGE_DELETE) {{
                setId(reference.getActivityId());
                applyConversationReference(reference, false);
            }};

            LogActivity(deleteActivity);

            return null;
        });


        // process bot logic
        CompletableFuture<Void> result = next.next();

        // flush transcript at end of turn
        while (!transcript.isEmpty()) {
            Activity activity = transcript.poll();
            try {
                this.transcriptLogger.logActivityAsync(activity);
            } catch (RuntimeException err) {
                logger.error(String.format("Transcript poll failed : %1$s", err));
            }
        }

        return result;
    }

    private void LogActivity(Activity activity) {
        if (activity.getTimestamp() == null) {
            activity.setTimestamp(DateTime.now(DateTimeZone.UTC));
        }
        transcript.offer(activity);
    }
}



