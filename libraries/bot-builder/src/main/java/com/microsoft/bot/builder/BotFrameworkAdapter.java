// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.bot.builder;

import com.microsoft.bot.connector.ConnectorClient;
import com.microsoft.bot.connector.Conversations;
import com.microsoft.bot.connector.ExecutorFactory;
import com.microsoft.bot.connector.authentication.*;
import com.microsoft.bot.connector.rest.RestConnectorClient;
import com.microsoft.bot.schema.*;
import com.microsoft.rest.retry.RetryStrategy;
import org.apache.commons.lang3.StringUtils;
import sun.net.www.http.HttpClient;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * A bot adapter that can connect a bot to a service endpoint.
 * The bot adapter encapsulates authentication processes and sends
 * activities to and receives activities from the Bot Connector Service. When your
 * bot receives an activity, the adapter creates a context object, passes it to your
 * bot's application logic, and sends responses back to the user's channel.
 * <p>Use {@link #use(Middleware)} to add {@link Middleware} objects
 * to your adapter’s middleware collection. The adapter processes and directs
 * incoming activities in through the bot middleware pipeline to your bot’s logic
 * and then back out again. As each activity flows in and out of the bot, each piece
 * of middleware can inspect or act upon the activity, both before and after the bot
 * logic runs.</p>
 * <p>
 * {@linkalso TurnContext}
 * {@linkalso Activity}
 * {@linkalso Bot}
 * {@linkalso Middleware}
 */
public class BotFrameworkAdapter extends BotAdapter {
    private final CredentialProvider _credentialProvider;

    private final RetryStrategy connectorClientRetryStrategy;
    private final String InvokeReponseKey = "BotFrameworkAdapter.InvokeResponse";
    private Map<String, MicrosoftAppCredentials> appCredentialMap = new HashMap<String, MicrosoftAppCredentials>();
    private boolean isEmulatingOAuthCards = false;

    /**
     * Initializes a new instance of the {@link BotFrameworkAdapter} class,
     * using a credential provider.
     *
     * @param credentialProvider The credential provider.
     * @throws IllegalArgumentException {@code credentialProvider} is {@code null}.
     *                                  Use a {@link MiddlewareSet} object to add multiple middleware
     *                                  components in the conustructor. Use the {@link #use(Middleware)} method to
     *                                  add additional middleware to the adapter after construction.
     */
    public BotFrameworkAdapter(CredentialProvider credentialProvider) {
        this(credentialProvider, null, null, null);
    }

    public BotFrameworkAdapter(CredentialProvider credentialProvider, RetryStrategy connectorClientRetryStrategy) {
        this(credentialProvider, connectorClientRetryStrategy, null, null);
    }

    public BotFrameworkAdapter(CredentialProvider credentialProvider, RetryStrategy connectorClientRetryStrategy, HttpClient httpClient) {
        this(credentialProvider, connectorClientRetryStrategy, httpClient, null);
    }

    public BotFrameworkAdapter(CredentialProvider credentialProvider, RetryStrategy connectorClientRetryStrategy, HttpClient httpClient, Middleware middleware) {
        if (credentialProvider == null)
            throw new IllegalArgumentException("credentialProvider");
        _credentialProvider = credentialProvider;
        this.connectorClientRetryStrategy = connectorClientRetryStrategy;

        if (middleware != null) {
            this.Use(middleware);
        }
    }

    /**
     * Initializes a new instance of the {@link BotFrameworkAdapter} class,
     * using an application ID and secret.
     *
     * @param appId       The application ID of the bot.
     * @param appPassword The application secret for the bot.
     */
    public BotFrameworkAdapter(String appId, String appPassword) {
        this(appId, appPassword, null, null, null);
    }

    public BotFrameworkAdapter(String appId, String appPassword, RetryStrategy connectorClientRetryStrategy) {
        this(appId, appPassword, connectorClientRetryStrategy, null, null);
    }

    public BotFrameworkAdapter(String appId, String appPassword, RetryStrategy connectorClientRetryStrategy, HttpClient httpClient) {
        this(appId, appPassword, connectorClientRetryStrategy, httpClient, null);
    }

    public BotFrameworkAdapter(String appId, String appPassword, RetryStrategy connectorClientRetryStrategy, HttpClient httpClient, Middleware middleware) {
        this(new SimpleCredentialProvider(appId, appPassword), connectorClientRetryStrategy, httpClient, middleware);
    }

    /**
     * Sends a proactive message from the bot to a conversation.
     *
     * @param botAppId  The application ID of the bot. This is the appId returned by Portal registration, and is
     *                  generally found in the "MicrosoftAppId" parameter in appSettings.json.
     * @param reference A reference to the conversation to continue.
     * @param callback  The method to call for the resulting bot turn.
     * @return A task that represents the work queued to execute.
     * @throws IllegalArgumentException {@code botAppId}, {@code reference}, or
     *                                  {@code callback} is {@code null}.
     *                                  Call this method to proactively send a message to a conversation.
     *                                  Most channels require a user to initaiate a conversation with a bot
     *                                  before the bot can send activities to the user.
     *                                  <p>This method registers the following.services().for the turn.<list type="bullet
     *                                  <item>{@link ConnectorClient}, the channel connector client to use this turn.</item>
     *                                  </list></p>
     *                                  <p>
     *                                  This overload differers from the Node implementation by requiring the BotId to be
     *                                  passed in. The .Net code allows multiple bots to be hosted in a single adapter which
     *                                  isn't something supported by Node.
     *                                  </p>
     *                                  <p>
     *                                  {@linkalso ProcessActivity(String, Activity, Func { TurnContext, Task })}
     *                                  {@linkalso BotAdapter.RunPipeline(TurnContext, Func { TurnContext, Task } }
     */
    @Override
    public CompletableFuture<Void> continueConversation(String botAppId, ConversationReference reference, BotCallbackHandler callback) {
        if (StringUtils.isEmpty(botAppId))
            throw new IllegalArgumentException("botAppId");

        if (reference == null)
            throw new IllegalArgumentException("reference");

        if (callback == null)
            throw new IllegalArgumentException("callback");

        TurnContextImpl context = new TurnContextImpl(this, new ConversationReferenceHelper(reference).getPostToBotMessage());

        // Hand craft Claims Identity.
        HashMap<String, String> claims = new HashMap<String, String>();
        claims.put(AuthenticationConstants.AUDIENCE_CLAIM, botAppId);
        claims.put(AuthenticationConstants.APPID_CLAIM, botAppId);
        ClaimsIdentity claimsIdentity = new ClaimsIdentity("ExternalBearer", claims);

        context.getTurnState().add(TurnContextStateNames.BOT_IDENTITY, claimsIdentity);

        ConnectorClient connectorClient = this.CreateConnectorClientAsync(reference.getServiceUrl(), claimsIdentity).join();
        context.getTurnState().add(TurnContextStateNames.CONNECTOR_CLIENT, connectorClient);
        return runPipeline(context, callback);
    }

    /**
     * Adds middleware to the adapter's pipeline.
     *
     * @param middleware The middleware to add.
     * @return The updated adapter object.
     * Middleware is added to the adapter at initialization time.
     * For each turn, the adapter calls middleware in the order in which you added it.
     */

    public BotFrameworkAdapter Use(Middleware middleware) {
        super._middlewareSet.use(middleware);
        return this;
    }

    /**
     * Creates a turn context and runs the middleware pipeline for an incoming activity.
     *
     * @param authHeader The HTTP authentication header of the request.
     * @param activity   The incoming activity.
     * @param callback   The code to run at the end of the adapter's middleware
     *                   pipeline.
     * @return A task that represents the work queued to execute. If the activity type
     * was 'Invoke' and the corresponding key (channelId + activityId) was found
     * then an InvokeResponse is returned, otherwise null is returned.
     * @throws IllegalArgumentException {@code activity} is {@code null}.
     */
    public CompletableFuture<InvokeResponse> ProcessActivity(String authHeader, Activity activity, Function<TurnContextImpl, CompletableFuture> callback) throws Exception {
        BotAssert.activityNotNull(activity);

        //ClaimsIdentity claimsIdentity = await(JwtTokenValidation.validateAuthHeader(activity, authHeader, _credentialProvider));

        //return completedFuture(await(ProcessActivity(claimsIdentity, activity, callback)));
        return completedFuture(null);
    }

    public CompletableFuture<InvokeResponse> ProcessActivity(ClaimsIdentity identity, Activity activity, BotCallbackHandler callback) throws Exception {
        BotAssert.activityNotNull(activity);

        try (TurnContextImpl context = new TurnContextImpl(this, activity)) {
            context.getTurnState().add("BotIdentity", identity);

            ConnectorClient connectorClient = this.CreateConnectorClientAsync(activity.getServiceUrl(), identity).join();
            // TODO: Verify key that C# uses
            context.getTurnState().add("ConnectorClient", connectorClient);

            super.runPipeline(context, callback);

            // Handle Invoke scenarios, which deviate from the request/response model in that
            // the Bot will return a specific body and return code.
            if (StringUtils.equals(activity.getType(), ActivityTypes.INVOKE)) {
                Activity invokeResponse = context.getTurnState().get(InvokeReponseKey);
                if (invokeResponse == null) {
                    // ToDo: Trace Here
                    throw new IllegalStateException("Bot failed to return a valid 'invokeResponse' activity.");
                } else {
                    return completedFuture((InvokeResponse) invokeResponse.getValue());
                }
            }

            // For all non-invoke scenarios, the HTTP layers above don't have to mess
            // withthe Body and return codes.
            return null;
        }
    }

    /**
     * Sends activities to the conversation.
     *
     * @param context    The context object for the turn.
     * @param activities The activities to send.
     * @return A task that represents the work queued to execute.
     * If the activities are successfully sent, the task result contains
     * an array of {@link ResourceResponse} objects containing the IDs that
     * the receiving channel assigned to the activities.
     * {@linkalso TurnContext.OnSendActivities(SendActivitiesHandler)}
     */
    public CompletableFuture<ResourceResponse[]> sendActivities(TurnContext context, Activity[] activities) {
        if (context == null) {
            throw new IllegalArgumentException("context");
        }

        if (activities == null) {
            throw new IllegalArgumentException("activities");
        }

        if (activities.length == 0) {
            throw new IllegalArgumentException("Expecting one or more activities, but the array was empty.");
        }

        ResourceResponse[] responses = new ResourceResponse[activities.length];

        /*
         * NOTE: we're using for here (vs. foreach) because we want to simultaneously index into the
         * activities array to get the activity to process as well as use that index to assign
         * the response to the responses array and this is the most cost effective way to do that.
         */
        for (int index = 0; index < activities.length; index++) {
            Activity activity = activities[index];
            ResourceResponse response = null;

            if (activity.getType().toString().equals("delay")) {
                // The Activity Schema doesn't have a delay type build in, so it's simulated
                // here in the Bot. This matches the behavior in the Node connector.
                int delayMs = (int) activity.getValue();
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                }
                //await(Task.Delay(delayMs));
                // No need to create a response. One will be created below.
            } else if (activity.getType().toString().equals("invokeResponse")) // Aligning name with Node
            {
                context.getTurnState().add(InvokeReponseKey, activity);
                // No need to create a response. One will be created below.
            } else if (StringUtils.equals(activity.getType(), ActivityTypes.TRACE) && !activity.getChannelId().equals("emulator")) {
                // if it is a Trace activity we only send to the channel if it's the emulator.
            } else if (!StringUtils.isEmpty(activity.getReplyToId())) {
                ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
                response = connectorClient.getConversations().replyToActivity(activity.getConversation().getId(), activity.getId(), activity).join();
            } else {
                ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
                response = connectorClient.getConversations().sendToConversation(activity.getConversation().getId(), activity).join();
            }

            // If No response is set, then defult to a "simple" response. This can't really be done
            // above, as there are cases where the ReplyTo/SendTo methods will also return null
            // (See below) so the check has to happen here.

            // Note: In addition to the Invoke / Delay / Activity cases, this code also applies
            // with Skype and Teams with regards to typing events.  When sending a typing event in
            // these channels they do not return a RequestResponse which causes the bot to blow up.
            // https://github.com/Microsoft/botbuilder-dotnet/issues/460
            // bug report : https://github.com/Microsoft/botbuilder-dotnet/issues/465
            if (response == null) {
                response = new ResourceResponse((activity.getId() == null) ? "" : activity.getId());
            }

            responses[index] = response;
        }

        return CompletableFuture.completedFuture(responses);
    }

    /**
     * Replaces an existing activity in the conversation.
     *
     * @param context  The context object for the turn.
     * @param activity New replacement activity.
     * @return A task that represents the work queued to execute.
     * If the activity is successfully sent, the task result contains
     * a {@link ResourceResponse} object containing the ID that the receiving
     * channel assigned to the activity.
     * <p>Before calling this, set the ID of the replacement activity to the ID
     * of the activity to replace.</p>
     * {@linkalso TurnContext.OnUpdateActivity(UpdateActivityHandler)}
     */
    @Override
    public CompletableFuture<ResourceResponse> updateActivity(TurnContext context, Activity activity) {
        ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
        // TODO String conversationId, String activityId, Activity activity)
        return connectorClient.getConversations().updateActivity(activity.getConversation().getId(), activity.getId(), activity);
    }

    /**
     * Deletes an existing activity in the conversation.
     *
     * @param context   The context object for the turn.
     * @param reference Conversation reference for the activity to delete.
     * @return A task that represents the work queued to execute.
     * {@linkalso TurnContext.OnDeleteActivity(DeleteActivityHandler)}
     */
    @Override
    public CompletableFuture<Void> deleteActivity(TurnContext context, ConversationReference reference) {
        RestConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
        try {
            connectorClient.getConversations().deleteConversationMember(
                reference.getConversation().getId(), reference.getActivityId()).join();
        } catch (CompletionException e) {
            e.printStackTrace();
            throw new RuntimeException(String.format("Failed deleting activity (%s)", e.toString()));
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Deletes a member from the current conversation
     *
     * @param context  The context object for the turn.
     * @param memberId ID of the member to delete from the conversation
     * @return
     */
    public void DeleteConversationMember(TurnContextImpl context, String memberId) {
        if (context.getActivity().getConversation() == null)
            throw new IllegalArgumentException("BotFrameworkAdapter.deleteConversationMember(): missing conversation");

        if (StringUtils.isEmpty(context.getActivity().getConversation().getId()))
            throw new IllegalArgumentException("BotFrameworkAdapter.deleteConversationMember(): missing conversation.id");

        ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");

        String conversationId = context.getActivity().getConversation().getId();

        // TODO:
        //await (connectorClient.conversations().DeleteConversationMemberAsync(conversationId, memberId));
        return;
    }

    /**
     * Lists the members of a given activity.
     *
     * @param context The context object for the turn.
     * @return List of Members of the activity
     */
    public CompletableFuture<List<ChannelAccount>> GetActivityMembers(TurnContextImpl context) {
        return GetActivityMembers(context, null);
    }

    public CompletableFuture<List<ChannelAccount>> GetActivityMembers(TurnContextImpl context, String activityId) {
        // If no activity was passed in, use the current activity.
        if (activityId == null)
            activityId = context.getActivity().getId();

        if (context.getActivity().getConversation() == null)
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation");

        if (StringUtils.isEmpty((context.getActivity().getConversation().getId())))
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation.id");

        ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
        String conversationId = context.getActivity().getConversation().getId();

        // TODO:
        //List<ChannelAccount> accounts = await(connectorClient.conversations().GetActivityMembersAsync(conversationId, activityId));

        return completedFuture(null);
    }

    /**
     * Lists the members of the current conversation.
     *
     * @param context The context object for the turn.
     * @return List of Members of the current conversation
     */
    public CompletableFuture<List<ChannelAccount>> GetConversationMembers(TurnContextImpl context) {
        if (context.getActivity().getConversation() == null)
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation");

        if (StringUtils.isEmpty(context.getActivity().getConversation().getId()))
            throw new IllegalArgumentException("BotFrameworkAdapter.GetActivityMembers(): missing conversation.id");

        ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
        String conversationId = context.getActivity().getConversation().getId();

        // TODO
        //List<ChannelAccount> accounts = await(connectorClient.conversations().getConversationMembersAsync(conversationId));
        return completedFuture(null);
    }

    /**
     * Lists the Conversations in which this bot has participated for a given channel server. The
     * channel server returns results in pages and each page will include a `continuationToken`
     * that can be used to fetch the next page of results from the server.
     *
     * @param serviceUrl  The URL of the channel server to query.  This can be retrieved
     *                    from `context.activity.serviceUrl`.
     * @param credentials The credentials needed for the Bot to connect to the.services().
     * @return List of Members of the current conversation
     * <p>
     * This overload may be called from outside the context of a conversation, as only the
     * Bot's ServiceUrl and credentials are required.
     */
    public CompletableFuture<ConversationsResult> GetConversations(String serviceUrl, MicrosoftAppCredentials credentials) throws MalformedURLException, URISyntaxException {
        return GetConversations(serviceUrl, credentials, null);
    }

    public CompletableFuture<ConversationsResult> GetConversations(String serviceUrl, MicrosoftAppCredentials credentials, String continuationToken) throws MalformedURLException, URISyntaxException {
        if (StringUtils.isEmpty(serviceUrl))
            throw new IllegalArgumentException("serviceUrl");

        if (credentials == null)
            throw new IllegalArgumentException("credentials");

        ConnectorClient connectorClient = this.CreateConnectorClient(serviceUrl, credentials);
        // TODO
        //ConversationsResult results = await(connectorClient.conversations().getConversationsAsync(continuationToken));
        return completedFuture(null);
    }

    /**
     * Lists the Conversations in which this bot has participated for a given channel server. The
     * channel server returns results in pages and each page will include a `continuationToken`
     * that can be used to fetch the next page of results from the server.
     *
     * @param context The context object for the turn.
     * @return List of Members of the current conversation
     * <p>
     * This overload may be called during standard Activity processing, at which point the Bot's
     * service URL and credentials that are part of the current activity processing pipeline
     * will be used.
     */
    public CompletableFuture<ConversationsResult> GetConversations(TurnContextImpl context) {
        return GetConversations(context, null);
    }

    public CompletableFuture<ConversationsResult> GetConversations(TurnContextImpl context, String continuationToken) {
        ConnectorClient connectorClient = context.getTurnState().get("ConnectorClient");
        // TODO
        //ConversationsResult results = await(connectorClient.conversations().getConversationsAsync());
        return completedFuture(null);
    }


    /**
     * Attempts to retrieve the token for a user that's in a login flow.
     *
     * @param context        Context for the current turn of conversation with the user.
     * @param connectionName Name of the auth connection to use.
     * @param magicCode      (Optional) Optional user entered code to validate.
     * @return Token Response
     */
    public CompletableFuture<TokenResponse> GetUserToken(TurnContextImpl context, String connectionName, String magicCode) {
        BotAssert.contextNotNull(context);
        if (context.getActivity().getFrom() == null || StringUtils.isEmpty(context.getActivity().getFrom().getId()))
            throw new IllegalArgumentException("BotFrameworkAdapter.GetuserToken(): missing from or from.id");

        if (StringUtils.isEmpty(connectionName))
            throw new IllegalArgumentException("connectionName");

        //OAuthClient client = this.CreateOAuthApiClient(context);
        //return await(client.GetUserTokenAsync(context.getActivity().getFrom().getId(), connectionName, magicCode));
        return completedFuture(null);
    }

    /**
     * Get the raw signin link to be sent to the user for signin for a connection name.
     *
     * @param context        Context for the current turn of conversation with the user.
     * @param connectionName Name of the auth connection to use.
     * @return
     */
    public CompletableFuture<String> GetOauthSignInLink(TurnContextImpl context, String connectionName) {
        BotAssert.contextNotNull(context);
        if (StringUtils.isEmpty(connectionName))
            throw new IllegalArgumentException("connectionName");

        //OAuthClient client = this.CreateOAuthApiClient(context);
        //return await(client.GetSignInLinkAsync(context.getActivity(), connectionName));
        return completedFuture(null);
    }

    /**
     * Signs the user out with the token server.
     *
     * @param context        Context for the current turn of conversation with the user.
     * @param connectionName Name of the auth connection to use.
     * @return
     */
    public CompletableFuture SignOutUser(TurnContextImpl context, String connectionName) {
        BotAssert.contextNotNull(context);
        if (StringUtils.isEmpty(connectionName))
            throw new IllegalArgumentException("connectionName");

        //OAuthClient client = this.CreateOAuthApiClient(context);
        //await(client.SignOutUserAsync(context.Activity.From.Id, connectionName));
        return completedFuture(null);
    }

    /**
     * Creates a conversation on the specified channel.
     *
     * @param channelId              The ID for the channel.
     * @param serviceUrl             The channel's service URL endpoint.
     * @param credentials            The application credentials for the bot.
     * @param conversationParameters The conversation information to use to
     *                               create the conversation.
     * @param callback               The method to call for the resulting bot turn.
     * @return A task that represents the work queued to execute.
     * To start a conversation, your bot must know its account information
     * and the user's account information on that channel.
     * Most channels only support initiating a direct message (non-group) conversation.
     * <p>The adapter attempts to create a new conversation on the channel, and
     * then sends a {@code conversationUpdate} activity through its middleware pipeline
     * to the {@code callback} method.</p>
     * <p>If the conversation is established with the
     * specified users, the ID of the activity's {@link Activity#getConversation}
     * will contain the ID of the new conversation.</p>
     */
    public CompletableFuture CreateConversation(String channelId, String serviceUrl, MicrosoftAppCredentials
        credentials, ConversationParameters conversationParameters, BotCallbackHandler callback) throws Exception {
        // Validate serviceUrl - can throw
        URI uri = new URI(serviceUrl);
        return CompletableFuture.runAsync(() -> {
            ConnectorClient connectorClient = null;
            try {
                connectorClient = this.CreateConnectorClient(serviceUrl, credentials);
            } catch (MalformedURLException e) {
                e.printStackTrace();
                throw new RuntimeException(String.format("Bad serviceUrl: %s", serviceUrl));
            } catch (URISyntaxException e) {
                e.printStackTrace();
                throw new RuntimeException(String.format("Bad serviceUrl: %s", serviceUrl));
            }

            Conversations conversations = connectorClient.getConversations();
            CompletableFuture<ConversationResourceResponse> result = conversations.createConversation(conversationParameters);

            ConversationResourceResponse response = result.join();

            // Create a conversation update activity to represent the result.
            Activity conversationUpdate = Activity.createConversationUpdateActivity();
            conversationUpdate.setChannelId(channelId);
            conversationUpdate.setTopicName(conversationParameters.getTopicName());
            conversationUpdate.setServiceUrl(serviceUrl);
            conversationUpdate.setMembersAdded(conversationParameters.getMembers());
            conversationUpdate.setId((response.getActivityId() != null) ? response.getActivityId() : UUID.randomUUID().toString());
            conversationUpdate.setConversation(new ConversationAccount(response.getId()));
            conversationUpdate.setRecipient(conversationParameters.getBot());

            try (TurnContextImpl context = new TurnContextImpl(this, conversationUpdate)) {
                try {
                    this.runPipeline(context, callback);
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("Running pipeline failed : %s", e));
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(String.format("Turn Context Error: %s", e));
            }
        }, ExecutorFactory.getExecutor());

    }

    protected CompletableFuture<Boolean> TrySetEmulatingOAuthCards(TurnContext turnContext) {
        if (!isEmulatingOAuthCards &&
            turnContext.getActivity().getChannelId().equals("emulator") &&
            (_credentialProvider.isAuthenticationDisabled().join())) {
            isEmulatingOAuthCards = true;
        }
        return completedFuture(isEmulatingOAuthCards);

    }

    protected OAuthClient CreateOAuthApiClient(TurnContext context) throws MalformedURLException, URISyntaxException {
        RestConnectorClient client = context.getTurnState().get("ConnectorClient");
        if (client == null) {
            throw new IllegalArgumentException("CreateOAuthApiClient: OAuth requires a valid ConnectorClient instance");
        }
        if (isEmulatingOAuthCards) {
            return new OAuthClient(client, context.getActivity().getServiceUrl());
        }
        return new OAuthClient(client, AuthenticationConstants.OAUTH_URL);
    }

    /**
     * Creates the connector client asynchronous.
     *
     * @param serviceUrl     The service URL.
     * @param claimsIdentity The claims identity.
     * @return ConnectorClient instance.
     * @throws UnsupportedOperationException ClaimsIdemtity cannot be null. Pass Anonymous ClaimsIdentity if authentication is turned off.
     */
    private CompletableFuture<ConnectorClient> CreateConnectorClientAsync(String serviceUrl, ClaimsIdentity claimsIdentity) {

        return CompletableFuture.supplyAsync(() -> {
            if (claimsIdentity == null) {
                throw new UnsupportedOperationException("ClaimsIdentity cannot be null. Pass Anonymous ClaimsIdentity if authentication is turned off.");
            }

            // For requests from channel App Id is in Audience claim of JWT token. For emulator it is in AppId claim. For
            // unauthenticated requests we have anonymous identity provided auth is disabled.
            if (claimsIdentity.claims() == null) {
                try {
                    return CreateConnectorClient(serviceUrl);
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("Invalid Service URL: %s", serviceUrl));
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("Invalid Service URL: %s", serviceUrl));
                }
            }

            // For Activities coming from Emulator AppId claim contains the Bot's AAD AppId.
            // For anonymous requests (requests with no header) appId is not set in claims.

            Map.Entry<String, String> botAppIdClaim = claimsIdentity.claims().entrySet().stream()
                .filter(claim -> claim.getKey() == AuthenticationConstants.AUDIENCE_CLAIM)
                .findFirst()
                .orElse(null);
            if (botAppIdClaim == null) {
                botAppIdClaim = claimsIdentity.claims().entrySet().stream()
                    .filter(claim -> claim.getKey() == AuthenticationConstants.APPID_CLAIM)
                    .findFirst()
                    .orElse(null);
            }

            if (botAppIdClaim != null) {
                String botId = botAppIdClaim.getValue();
                MicrosoftAppCredentials appCredentials = this.GetAppCredentials(botId).join();
                try {
                    return this.CreateConnectorClient(serviceUrl, appCredentials);
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("Bad Service URL: %s", serviceUrl));
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("Bad Service URL: %s", serviceUrl));
                }
            } else {
                try {
                    return this.CreateConnectorClient(serviceUrl);
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("Bad Service URL: %s", serviceUrl));
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    throw new RuntimeException(String.format("Bad Service URL: %s", serviceUrl));
                }
            }
        }, ExecutorFactory.getExecutor());

    }

    /**
     * Creates the connector client.
     *
     * @param serviceUrl The service URL.
     * @return Connector client instance.
     */
    private ConnectorClient CreateConnectorClient(String serviceUrl) throws MalformedURLException, URISyntaxException {
        return CreateConnectorClient(serviceUrl, null);
    }

    private ConnectorClient CreateConnectorClient(String serviceUrl, MicrosoftAppCredentials appCredentials) throws MalformedURLException, URISyntaxException {
        RestConnectorClient connectorClient = null;
        if (appCredentials != null) {
            connectorClient = new RestConnectorClient(new URI(serviceUrl).toURL().toString(), appCredentials);
        }
        // TODO: Constructor necessary?
//        else {
//
//            connectorClient = new ConnectorClientImpl(new URI(serviceUrl).toURL().toString());
//        }

        if (this.connectorClientRetryStrategy != null)
            connectorClient.setRestRetryStrategy(this.connectorClientRetryStrategy);


        return connectorClient;

    }

    /**
     * Gets the application credentials. App Credentials are cached so as to ensure we are not refreshing
     * token everytime.
     *
     * @param appId The application identifier (AAD Id for the bot).
     * @return App credentials.
     */
    private CompletableFuture<MicrosoftAppCredentials> GetAppCredentials(String appId) {
        CompletableFuture<MicrosoftAppCredentials> result = CompletableFuture.supplyAsync(() -> {
            if (appId == null) {
                return MicrosoftAppCredentials.empty();
            }
            if (this.appCredentialMap.containsKey(appId))
                return this.appCredentialMap.get(appId);
            String appPassword = this._credentialProvider.getAppPassword(appId).join();
            MicrosoftAppCredentials appCredentials = new MicrosoftAppCredentials(appId, appPassword);
            this.appCredentialMap.put(appId, appCredentials);
            return appCredentials;

        }, ExecutorFactory.getExecutor());
        return result;
    }

}
