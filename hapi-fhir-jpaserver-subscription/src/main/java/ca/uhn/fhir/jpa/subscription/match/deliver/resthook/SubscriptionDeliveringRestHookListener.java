/*-
 * #%L
 * HAPI FHIR Subscription Server
 * %%
 * Copyright (C) 2014 - 2025 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.subscription.match.deliver.resthook;

import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.subscription.match.deliver.BaseSubscriptionDeliveryListener;
import ca.uhn.fhir.jpa.subscription.model.CanonicalSubscription;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.client.api.Header;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.SimpleRequestHeaderInterceptor;
import ca.uhn.fhir.rest.gclient.IClientExecutable;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.messaging.BaseResourceModifiedMessage;
import ca.uhn.fhir.util.BundleUtil;
import ca.uhn.fhir.util.Logs;
import ca.uhn.fhir.util.StopWatch;
import jakarta.annotation.Nullable;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.messaging.MessagingException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Scope("prototype")
public class SubscriptionDeliveringRestHookListener extends BaseSubscriptionDeliveryListener {
	private static final Logger ourLog = LoggerFactory.getLogger(SubscriptionDeliveringRestHookListener.class);

	@Autowired
	private DaoRegistry myDaoRegistry;

	/**
	 * Constructor
	 */
	public SubscriptionDeliveringRestHookListener() {
		super();
	}

	public Class<ResourceDeliveryMessage> getPayloadType() {
		return ResourceDeliveryMessage.class;
	}

	protected void deliverPayload(
			ResourceDeliveryMessage theMsg,
			CanonicalSubscription theSubscription,
			EncodingEnum thePayloadType,
			IGenericClient theClient) {
		IBaseResource payloadResource = getAndMassagePayload(theMsg, theSubscription);

		// Regardless of whether we have a payload, the rest-hook should be sent.
		doDelivery(theMsg, theSubscription, thePayloadType, theClient, payloadResource);
	}

	protected void doDelivery(
			ResourceDeliveryMessage theMsg,
			CanonicalSubscription theSubscription,
			EncodingEnum thePayloadType,
			IGenericClient theClient,
			IBaseResource thePayloadResource) {
		IClientExecutable<?, ?> operation;

		if (theSubscription.isTopicSubscription()) {
			operation = createDeliveryRequestTopic((IBaseBundle) thePayloadResource, theClient);
		} else if (isNotBlank(theSubscription.getPayloadSearchCriteria())) {
			operation = createDeliveryRequestTransaction(theSubscription, theClient, thePayloadResource);
		} else if (thePayloadType != null) {
			operation = createDeliveryRequestNormal(theMsg, theClient, thePayloadResource);
		} else {
			sendNotification(theMsg);
			operation = null;
		}

		if (operation != null) {

			if (thePayloadType != null) {
				operation.encoded(thePayloadType);
			}

			String payloadId = thePayloadResource.getIdElement().toUnqualified().getValue();
			StopWatch sw = new StopWatch();

			try {
				operation.execute();
			} catch (ResourceNotFoundException e) {
				ourLog.error("Cannot reach {} ", theMsg.getSubscription().getEndpointUrl());
				ourLog.error("Exception: ", e);
				throw e;
			}

			Logs.getSubscriptionTroubleshootingLog()
					.debug(
							"Delivered {} rest-hook payload {} for {} in {}",
							theMsg.getOperationType(),
							payloadId,
							theSubscription
									.getIdElement(myFhirContext)
									.toUnqualifiedVersionless()
									.getValue(),
							sw);
		}
	}

	@Nullable
	private IClientExecutable<?, ?> createDeliveryRequestNormal(
			ResourceDeliveryMessage theMsg, IGenericClient theClient, IBaseResource thePayloadResource) {
		IClientExecutable<?, ?> operation;
		switch (theMsg.getOperationType()) {
			case CREATE:
			case UPDATE:
				operation = theClient.update().resource(thePayloadResource);
				break;
			case DELETE:
				operation = theClient.delete().resourceById(theMsg.getPayloadId(myFhirContext));
				break;
			default:
				ourLog.warn("Ignoring delivery message of type: {}", theMsg.getOperationType());
				operation = null;
				break;
		}
		return operation;
	}

	private IClientExecutable<?, ?> createDeliveryRequestTransaction(
			CanonicalSubscription theSubscription, IGenericClient theClient, IBaseResource thePayloadResource) {
		IBaseBundle bundle = createDeliveryBundleForPayloadSearchCriteria(theSubscription, thePayloadResource);
		return theClient.transaction().withBundle(bundle);
	}

	private IClientExecutable<?, ?> createDeliveryRequestTopic(IBaseBundle theBundle, IGenericClient theClient) {
		return theClient.transaction().withBundle(theBundle);
	}

	public IBaseResource getResource(IIdType thePayloadId, RequestPartitionId thePartitionId, boolean theDeletedOK)
			throws ResourceGoneException {
		RuntimeResourceDefinition resourceDef = myFhirContext.getResourceDefinition(thePayloadId.getResourceType());
		SystemRequestDetails systemRequestDetails = new SystemRequestDetails().setRequestPartitionId(thePartitionId);
		IFhirResourceDao<?> dao = myDaoRegistry.getResourceDao(resourceDef.getImplementingClass());
		return dao.read(thePayloadId.toVersionless(), systemRequestDetails, theDeletedOK);
	}

	/**
	 * Perform operations on the payload based on various subscription extension settings such as deliver latest version,
	 * delete and/or strip version id.
	 * @param theMsg
	 * @param theSubscription
	 * @return
	 */
	protected IBaseResource getAndMassagePayload(
			ResourceDeliveryMessage theMsg, CanonicalSubscription theSubscription) {
		IBaseResource payloadResource = theMsg.getPayload(myFhirContext);

		if (payloadResource instanceof IBaseBundle) {
			return getAndMassageBundle(theMsg, (IBaseBundle) payloadResource, theSubscription);
		} else {
			return getAndMassageResource(theMsg, payloadResource, theSubscription);
		}
	}

	private IBaseResource getAndMassageBundle(
			ResourceDeliveryMessage theMsg, IBaseBundle theBundle, CanonicalSubscription theSubscription) {
		BundleUtil.processEntries(myFhirContext, theBundle, entry -> {
			IBaseResource entryResource = entry.getResource();
			if (entryResource != null) {
				// SubscriptionStatus is a "virtual" resource type that is not stored in the repository
				if (!"SubscriptionStatus".equals(myFhirContext.getResourceType(entryResource))) {
					IBaseResource updatedResource = getAndMassageResource(theMsg, entryResource, theSubscription);
					entry.setFullUrl(updatedResource.getIdElement().getValue());
					entry.setResource(updatedResource);
				}
			}
		});
		return theBundle;
	}

	private IBaseResource getAndMassageResource(
			ResourceDeliveryMessage theMsg, IBaseResource thePayloadResource, CanonicalSubscription theSubscription) {
		if (thePayloadResource == null || theSubscription.getRestHookDetails().isDeliverLatestVersion()) {

			IIdType payloadId = theMsg.getPayloadId(myFhirContext).toVersionless();
			if (theSubscription.isTopicSubscription()) {
				payloadId = thePayloadResource.getIdElement().toVersionless();
			}
			try {
				if (payloadId != null) {
					boolean deletedOK =
							theMsg.getOperationType() == BaseResourceModifiedMessage.OperationTypeEnum.DELETE;
					thePayloadResource = getResource(payloadId, theMsg.getPartitionId(), deletedOK);
				} else {
					return null;
				}
			} catch (ResourceGoneException e) {
				ourLog.warn(
						"Resource {} is deleted, not going to deliver for subscription {}",
						payloadId,
						theSubscription.getIdElement(myFhirContext));
				return null;
			}
		}

		IIdType resourceId = thePayloadResource.getIdElement();
		if (theSubscription.getRestHookDetails().isStripVersionId()) {
			resourceId = resourceId.toVersionless();
			thePayloadResource.setId(resourceId);
			thePayloadResource.getMeta().setVersionId(null);
		}
		return thePayloadResource;
	}

	@Override
	public void handleMessage(ResourceDeliveryMessage theMessage) throws MessagingException {
		CanonicalSubscription subscription = theMessage.getSubscription();

		// Interceptor call: SUBSCRIPTION_BEFORE_REST_HOOK_DELIVERY
		HookParams params = new HookParams()
				.add(CanonicalSubscription.class, subscription)
				.add(ResourceDeliveryMessage.class, theMessage);
		if (!getInterceptorBroadcaster().callHooks(Pointcut.SUBSCRIPTION_BEFORE_REST_HOOK_DELIVERY, params)) {
			return;
		}

		// Grab the endpoint from the subscription
		String endpointUrl = subscription.getEndpointUrl();

		// Grab the payload type (encoding mimetype) from the subscription
		String payloadString = subscription.getPayloadString();
		EncodingEnum payloadType = null;
		if (payloadString != null) {
			payloadType = EncodingEnum.forContentType(payloadString);
		}

		// Create the client request
		myFhirContext.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		IGenericClient client = null;
		if (isNotBlank(endpointUrl)) {
			client = myFhirContext.newRestfulGenericClient(endpointUrl);

			// Additional headers specified in the subscription
			List<String> headers = subscription.getHeaders();
			for (String next : headers) {
				if (isNotBlank(next)) {
					client.registerInterceptor(new SimpleRequestHeaderInterceptor(next));
				}
			}
		}

		deliverPayload(theMessage, subscription, payloadType, client);

		// Interceptor call: SUBSCRIPTION_AFTER_REST_HOOK_DELIVERY
		params = new HookParams()
				.add(CanonicalSubscription.class, subscription)
				.add(ResourceDeliveryMessage.class, theMessage);
		if (!getInterceptorBroadcaster().callHooks(Pointcut.SUBSCRIPTION_AFTER_REST_HOOK_DELIVERY, params)) {
			//noinspection UnnecessaryReturnStatement
			return;
		}
	}

	/**
	 * Sends a POST notification without a payload
	 */
	protected void sendNotification(ResourceDeliveryMessage theMsg) {
		Map<String, List<String>> params = new HashMap<>();
		CanonicalSubscription subscription = theMsg.getSubscription();
		List<Header> headers = parseHeadersFromSubscription(subscription);

		StringBuilder url = new StringBuilder(subscription.getEndpointUrl());
		IHttpClient client =
				myFhirContext.getRestfulClientFactory().getHttpClient(url, params, "", RequestTypeEnum.POST, headers);
		IHttpRequest request = client.createParamRequest(myFhirContext, params, null);
		try {
			IHttpResponse response = request.execute();
			// close connection in order to return a possible cached connection to the connection pool
			response.close();
		} catch (IOException e) {
			ourLog.error(
					"Error trying to reach {}: {}", theMsg.getSubscription().getEndpointUrl(), e.toString());
			throw new ResourceNotFoundException(Msg.code(5) + e.getMessage());
		}
	}

	public static List<Header> parseHeadersFromSubscription(CanonicalSubscription subscription) {
		List<Header> headers = null;
		if (subscription != null) {
			for (String h : subscription.getHeaders()) {
				if (h != null) {
					final int sep = h.indexOf(':');
					if (sep > 0) {
						final String name = h.substring(0, sep);
						final String value = h.substring(sep + 1);
						if (isNotBlank(name)) {
							if (headers == null) {
								headers = new ArrayList<>();
							}
							headers.add(new Header(name.trim(), value.trim()));
						}
					}
				}
			}
		}
		if (headers == null) {
			headers = Collections.emptyList();
		} else {
			headers = Collections.unmodifiableList(headers);
		}
		return headers;
	}
}
