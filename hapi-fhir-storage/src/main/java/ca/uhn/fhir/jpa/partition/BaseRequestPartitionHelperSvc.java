/*-
 * #%L
 * HAPI FHIR Storage api
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
package ca.uhn.fhir.jpa.partition;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.interceptor.api.HookParams;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.model.ReadPartitionIdRequestDetails;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import ca.uhn.fhir.rest.server.util.CompositeInterceptorBroadcaster;
import ca.uhn.fhir.util.Logs;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class BaseRequestPartitionHelperSvc implements IRequestPartitionHelperSvc {

	public static final Logger ourLog = Logs.getPartitionTroubleshootingLog();
	private final HashSet<Object> myNonPartitionableResourceNames;

	@Autowired
	protected FhirContext myFhirContext;

	@Autowired
	private IInterceptorBroadcaster myInterceptorBroadcaster;

	PartitionSettings myPartitionSettings;

	protected BaseRequestPartitionHelperSvc() {
		myNonPartitionableResourceNames = new HashSet<>();

		// Infrastructure
		myNonPartitionableResourceNames.add("SearchParameter");

		// Validation and Conformance
		myNonPartitionableResourceNames.add("StructureDefinition");
		myNonPartitionableResourceNames.add("Questionnaire");
		myNonPartitionableResourceNames.add("CapabilityStatement");
		myNonPartitionableResourceNames.add("CompartmentDefinition");
		myNonPartitionableResourceNames.add("OperationDefinition");

		myNonPartitionableResourceNames.add("Library");

		// Terminology
		myNonPartitionableResourceNames.add("ConceptMap");
		myNonPartitionableResourceNames.add("CodeSystem");
		myNonPartitionableResourceNames.add("ValueSet");
		myNonPartitionableResourceNames.add("NamingSystem");
		myNonPartitionableResourceNames.add("StructureMap");
	}

	@Autowired
	public void setPartitionSettings(PartitionSettings thePartitionSettings) {
		myPartitionSettings = thePartitionSettings;
	}

	/**
	 * Invoke the {@link Pointcut#STORAGE_PARTITION_IDENTIFY_READ} interceptor pointcut to determine the tenant for a read request.
	 * <p>
	 * If no interceptors are registered with a hook for {@link Pointcut#STORAGE_PARTITION_IDENTIFY_READ}, return
	 * {@link RequestPartitionId#allPartitions()} instead.
	 */
	@Nonnull
	@Override
	public RequestPartitionId determineReadPartitionForRequest(
			@Nullable RequestDetails theRequest, @Nonnull ReadPartitionIdRequestDetails theDetails) {
		if (!myPartitionSettings.isPartitioningEnabled()) {
			return RequestPartitionId.allPartitions();
		}

		// certain use-cases (e.g. batch2 jobs), only have resource type populated in the ReadPartitionIdRequestDetails
		// TODO MM: see if we can make RequestDetails consistent
		String resourceType = theDetails.getResourceType();

		RequestDetails requestDetails = theRequest;
		// TODO GGG eventually, theRequest will not be allowed to be null here, and we will pass through
		// SystemRequestDetails instead.
		if (requestDetails == null) {
			requestDetails = new SystemRequestDetails();
			logSubstitutingDefaultSystemRequestDetails();
		}

		boolean nonPartitionableResource = isResourceNonPartitionable(resourceType);

		RequestPartitionId requestPartitionId = null;
		// Handle system requests
		if (requestDetails instanceof SystemRequestDetails
				&& systemRequestHasExplicitPartition((SystemRequestDetails) requestDetails)
				&& !nonPartitionableResource) {
			requestPartitionId = getSystemRequestPartitionId((SystemRequestDetails) requestDetails, false);
			logSystemRequestDetailsResolution((SystemRequestDetails) requestDetails);

		} else if ((requestDetails instanceof SystemRequestDetails) && nonPartitionableResource) {
			requestPartitionId = myPartitionSettings.getDefaultRequestPartitionId();
			logSystemRequestDetailsResolution((SystemRequestDetails) requestDetails);
			logNonPartitionableType(resourceType);
		} else {
			// TODO mb: why is this path different than create?
			//  Here, a non-partitionable resource is still delivered to the pointcuts.
			IInterceptorBroadcaster compositeBroadcaster =
					CompositeInterceptorBroadcaster.newCompositeBroadcaster(myInterceptorBroadcaster, requestDetails);
			if (compositeBroadcaster.hasHooks(Pointcut.STORAGE_PARTITION_IDENTIFY_ANY)) {
				requestPartitionId = callAnyPointcut(compositeBroadcaster, requestDetails);
			} else if (compositeBroadcaster.hasHooks(Pointcut.STORAGE_PARTITION_IDENTIFY_READ)) {
				requestPartitionId = callReadPointcut(compositeBroadcaster, requestDetails, theDetails);
			}
		}

		validateRequestPartitionNotNull(
				requestPartitionId, Pointcut.STORAGE_PARTITION_IDENTIFY_ANY, Pointcut.STORAGE_PARTITION_IDENTIFY_READ);

		RequestPartitionId resultRequestPartitionId =
				validateAndNormalizePartition(requestPartitionId, requestDetails, resourceType);
		logTroubleshootingResult("read", resourceType, theRequest, resultRequestPartitionId);

		return resultRequestPartitionId;
	}

	private static RequestPartitionId callAnyPointcut(
			IInterceptorBroadcaster compositeBroadcaster, RequestDetails requestDetails) {
		// Interceptor call: STORAGE_PARTITION_IDENTIFY_ANY
		HookParams params = new HookParams()
				.add(RequestDetails.class, requestDetails)
				.addIfMatchesType(ServletRequestDetails.class, requestDetails);

		return callAndLog(compositeBroadcaster, Pointcut.STORAGE_PARTITION_IDENTIFY_ANY, params);
	}

	private static RequestPartitionId callCreatePointcut(
			IInterceptorBroadcaster compositeBroadcaster,
			RequestDetails requestDetails,
			@Nonnull IBaseResource theResource) {
		// Interceptor call: STORAGE_PARTITION_IDENTIFY_CREATE
		HookParams params = new HookParams()
				.add(IBaseResource.class, theResource)
				.add(RequestDetails.class, requestDetails)
				.addIfMatchesType(ServletRequestDetails.class, requestDetails);

		return callAndLog(compositeBroadcaster, Pointcut.STORAGE_PARTITION_IDENTIFY_CREATE, params);
	}

	private static RequestPartitionId callAndLog(
			IInterceptorBroadcaster compositeBroadcaster, Pointcut pointcut, HookParams params) {
		RequestPartitionId result =
				(RequestPartitionId) compositeBroadcaster.callHooksAndReturnObject(pointcut, params);

		if (ourLog.isTraceEnabled()) {
			ourLog.trace(
					"{}: result={} hooks={}", pointcut, result, compositeBroadcaster.getInvokersForPointcut(pointcut));
		}
		return result;
	}

	private static RequestPartitionId callReadPointcut(
			IInterceptorBroadcaster compositeBroadcaster,
			RequestDetails requestDetails,
			@Nonnull ReadPartitionIdRequestDetails theDetails) {
		// Interceptor call: STORAGE_PARTITION_IDENTIFY_READ
		HookParams params = new HookParams()
				.add(RequestDetails.class, requestDetails)
				.addIfMatchesType(ServletRequestDetails.class, requestDetails)
				.add(ReadPartitionIdRequestDetails.class, theDetails);

		return callAndLog(compositeBroadcaster, Pointcut.STORAGE_PARTITION_IDENTIFY_READ, params);
	}

	private static void logNonPartitionableType(String theResourceType) {
		ourLog.trace("Partitioning: resource type {} must be on the DEFAULT partition.", theResourceType);
	}

	@Override
	public RequestPartitionId determineGenericPartitionForRequest(RequestDetails theRequestDetails) {
		RequestPartitionId requestPartitionId = null;

		if (!myPartitionSettings.isPartitioningEnabled()) {
			return RequestPartitionId.allPartitions();
		}

		if (theRequestDetails instanceof SystemRequestDetails
				&& systemRequestHasExplicitPartition((SystemRequestDetails) theRequestDetails)) {
			requestPartitionId = getSystemRequestPartitionId((SystemRequestDetails) theRequestDetails);
			logSystemRequestDetailsResolution((SystemRequestDetails) theRequestDetails);
		} else {
			IInterceptorBroadcaster compositeBroadcaster = CompositeInterceptorBroadcaster.newCompositeBroadcaster(
					myInterceptorBroadcaster, theRequestDetails);
			if (compositeBroadcaster.hasHooks(Pointcut.STORAGE_PARTITION_IDENTIFY_ANY)) {
				requestPartitionId = callAnyPointcut(compositeBroadcaster, theRequestDetails);
			}
		}

		// TODO MM: at the moment it is ok for this method to return null
		// check if it can be made consistent and it's implications
		// validateRequestPartitionNotNull(requestPartitionId, Pointcut.STORAGE_PARTITION_IDENTIFY_ANY);

		if (requestPartitionId != null) {
			requestPartitionId = validateAndNormalizePartition(
					requestPartitionId, theRequestDetails, theRequestDetails.getResourceName());
		}

		logTroubleshootingResult("generic", theRequestDetails.getResourceName(), theRequestDetails, requestPartitionId);

		return requestPartitionId;
	}

	/**
	 * For system requests, read partition from tenant ID if present, otherwise set to DEFAULT. If the resource they are attempting to partition
	 * is non-partitionable scream in the logs and set the partition to DEFAULT.
	 */
	private RequestPartitionId getSystemRequestPartitionId(
			SystemRequestDetails theRequest, boolean theNonPartitionableResource) {
		RequestPartitionId requestPartitionId;
		requestPartitionId = getSystemRequestPartitionId(theRequest);
		if (theNonPartitionableResource
				&& !requestPartitionId.isPartition(myPartitionSettings.getDefaultPartitionId())) {
			throw new InternalErrorException(Msg.code(1315)
					+ "System call is attempting to write a non-partitionable resource to a partition! This is a bug!");
		}
		return requestPartitionId;
	}

	/**
	 * Determine the partition for a System Call (defined by the fact that the request is of type SystemRequestDetails)
	 * <p>
	 * 1. If the tenant ID is set to the constant for all partitions, return all partitions
	 * 2. If there is a tenant ID set in the request, use it.
	 * 3. Otherwise, return the Default Partition.
	 *
	 * @param theRequest The {@link SystemRequestDetails}
	 * @return the {@link RequestPartitionId} to be used for this request.
	 */
	@Nonnull
	private RequestPartitionId getSystemRequestPartitionId(@Nonnull SystemRequestDetails theRequest) {
		if (theRequest.getRequestPartitionId() != null) {
			return theRequest.getRequestPartitionId();
		}
		if (theRequest.getTenantId() != null) {
			// TODO: JA2 we should not be inferring the partition name from the tenant name
			return RequestPartitionId.fromPartitionName(theRequest.getTenantId());
		} else {
			return RequestPartitionId.defaultPartition();
		}
	}

	/**
	 * Invoke the {@link Pointcut#STORAGE_PARTITION_IDENTIFY_CREATE} interceptor pointcut to determine the tenant for a create request.
	 */
	@Nonnull
	@Override
	public RequestPartitionId determineCreatePartitionForRequest(
			@Nullable final RequestDetails theRequest,
			@Nonnull IBaseResource theResource,
			@Nonnull String theResourceType) {

		if (!myPartitionSettings.isPartitioningEnabled()) {
			return RequestPartitionId.allPartitions();
		}

		RequestDetails requestDetails = theRequest;
		boolean nonPartitionableResource = isResourceNonPartitionable(theResourceType);

		// TODO GGG eventually, theRequest will not be allowed to be null here, and we will pass through
		// SystemRequestDetails instead.
		if (theRequest == null) {
			requestDetails = new SystemRequestDetails();
			logSubstitutingDefaultSystemRequestDetails();
		}

		RequestPartitionId requestPartitionId = null;
		if (theRequest instanceof SystemRequestDetails
				&& systemRequestHasExplicitPartition((SystemRequestDetails) theRequest)) {
			requestPartitionId =
					getSystemRequestPartitionId((SystemRequestDetails) theRequest, nonPartitionableResource);

			logSystemRequestDetailsResolution((SystemRequestDetails) theRequest);
		} else {
			IInterceptorBroadcaster compositeBroadcaster =
					CompositeInterceptorBroadcaster.newCompositeBroadcaster(myInterceptorBroadcaster, requestDetails);
			if (compositeBroadcaster.hasHooks(Pointcut.STORAGE_PARTITION_IDENTIFY_ANY)) {
				requestPartitionId = callAnyPointcut(compositeBroadcaster, requestDetails);
			} else if (compositeBroadcaster.hasHooks(Pointcut.STORAGE_PARTITION_IDENTIFY_CREATE)) {
				requestPartitionId = callCreatePointcut(compositeBroadcaster, requestDetails, theResource);
			}
		}

		// If the interceptors haven't selected a partition, and its a non-partitionable resource anyhow, send
		// to DEFAULT
		if (nonPartitionableResource && requestPartitionId == null) {
			logNonPartitionableType(theResourceType);
			requestPartitionId = myPartitionSettings.getDefaultRequestPartitionId();
		}

		validateRequestPartitionNotNull(
				requestPartitionId,
				Pointcut.STORAGE_PARTITION_IDENTIFY_CREATE,
				Pointcut.STORAGE_PARTITION_IDENTIFY_ANY);
		validatePartitionForCreate(requestPartitionId, theResourceType);

		RequestPartitionId resultRequestPartitionId =
				validateAndNormalizePartition(requestPartitionId, requestDetails, theResourceType);

		logTroubleshootingResult("create", theResourceType, theRequest, resultRequestPartitionId);

		return resultRequestPartitionId;
	}

	private boolean systemRequestHasExplicitPartition(@Nonnull SystemRequestDetails theRequest) {
		return theRequest.getRequestPartitionId() != null || theRequest.getTenantId() != null;
	}

	@Nonnull
	@Override
	public Set<Integer> toReadPartitions(@Nonnull RequestPartitionId theRequestPartitionId) {
		return theRequestPartitionId.getPartitionIds().stream()
				.map(t -> t == null ? myPartitionSettings.getDefaultPartitionId() : t)
				.collect(Collectors.toSet());
	}

	/**
	 * If the partition only has a name but not an ID, this method resolves the ID.
	 * <p>
	 * If the partition has an ID but not a name, the name is resolved.
	 * <p>
	 * If the partition has both, they are validated to ensure that they correspond.
	 */
	@Nonnull
	private RequestPartitionId validateAndNormalizePartition(
			@Nonnull RequestPartitionId theRequestPartitionId,
			RequestDetails theRequest,
			@Nullable String theResourceType) {
		RequestPartitionId retVal = theRequestPartitionId;

		if (!myPartitionSettings.isUnnamedPartitionMode()) {
			if (retVal.getPartitionNames() != null) {
				retVal = validateAndNormalizePartitionNames(retVal);
			} else if (retVal.hasPartitionIds()) {
				retVal = validateAndNormalizePartitionIds(retVal);
			}
		}

		// Note: It's still possible that the partition only has a date but no name/id

		if (StringUtils.isNotBlank(theResourceType)) {
			validateHasPartitionPermissions(theRequest, theResourceType, retVal);
		}

		// Replace null partition ID with non-null default partition ID if one is being used
		if (myPartitionSettings.getDefaultPartitionId() != null
				&& retVal.hasPartitionIds()
				&& retVal.hasDefaultPartitionId(null)) {
			List<Integer> partitionIds = new ArrayList<>(retVal.getPartitionIds());
			for (int i = 0; i < partitionIds.size(); i++) {
				if (partitionIds.get(i) == null) {
					partitionIds.set(i, myPartitionSettings.getDefaultPartitionId());
				}
			}
			retVal = RequestPartitionId.fromPartitionIds(partitionIds);
		}

		ourLog.trace("Partition normalization: {} -> {}", theRequestPartitionId, retVal);

		return retVal;
	}

	@Override
	public void validateHasPartitionPermissions(
			@Nonnull RequestDetails theRequest, String theResourceType, RequestPartitionId theRequestPartitionId) {
		IInterceptorBroadcaster compositeBroadcaster =
				CompositeInterceptorBroadcaster.newCompositeBroadcaster(myInterceptorBroadcaster, theRequest);
		if (compositeBroadcaster.hasHooks(Pointcut.STORAGE_PARTITION_SELECTED)) {
			RuntimeResourceDefinition runtimeResourceDefinition = null;
			if (theResourceType != null) {
				runtimeResourceDefinition = myFhirContext.getResourceDefinition(theResourceType);
			}
			HookParams params = new HookParams()
					.add(RequestPartitionId.class, theRequestPartitionId)
					.add(RequestDetails.class, theRequest)
					.addIfMatchesType(ServletRequestDetails.class, theRequest)
					.add(RuntimeResourceDefinition.class, runtimeResourceDefinition);
			compositeBroadcaster.callHooks(Pointcut.STORAGE_PARTITION_SELECTED, params);
		}
	}

	@Override
	public boolean isResourcePartitionable(String theResourceType) {
		return theResourceType != null && !myNonPartitionableResourceNames.contains(theResourceType);
	}

	@Override
	@Nullable
	public Integer getDefaultPartitionId() {
		return myPartitionSettings.getDefaultPartitionId();
	}

	private boolean isResourceNonPartitionable(String theResourceType) {
		return theResourceType != null && !isResourcePartitionable(theResourceType);
	}

	private void validatePartitionForCreate(RequestPartitionId theRequestPartitionId, String theResourceName) {
		if (theRequestPartitionId.hasPartitionIds()) {
			validateSinglePartitionIdOrName(theRequestPartitionId.getPartitionIds());
		}
		validateSinglePartitionIdOrName(theRequestPartitionId.getPartitionNames());

		// Make sure we're not using one of the conformance resources in a non-default partition
		if (isDefaultPartition(theRequestPartitionId) || theRequestPartitionId.isAllPartitions()) {
			return;
		}

		// TODO MM: check if we need to validate using the configured value PartitionSettings.defaultPartition
		// however that is only used for read and not for create at the moment
		if ((theRequestPartitionId.hasPartitionIds()
						&& !theRequestPartitionId.getPartitionIds().contains(null))
				|| (theRequestPartitionId.hasPartitionNames()
						&& !theRequestPartitionId.getPartitionNames().contains(JpaConstants.DEFAULT_PARTITION_NAME))) {

			if (isResourceNonPartitionable(theResourceName)) {
				String msg = myFhirContext
						.getLocalizer()
						.getMessageSanitized(
								BaseRequestPartitionHelperSvc.class,
								"nonDefaultPartitionSelectedForNonPartitionable",
								theResourceName);
				throw new UnprocessableEntityException(Msg.code(1318) + msg);
			}
		}
	}

	private static void validateRequestPartitionNotNull(
			RequestPartitionId theRequestPartitionId, Pointcut... thePointcuts) {
		if (theRequestPartitionId == null) {
			throw new InternalErrorException(
					Msg.code(1319) + "No interceptor provided a value for pointcuts: " + Arrays.toString(thePointcuts));
		}
	}

	private static void validateSinglePartitionIdOrName(@Nullable List<?> thePartitionIds) {
		if (thePartitionIds != null && thePartitionIds.size() != 1) {
			throw new InternalErrorException(
					Msg.code(1320) + "RequestPartitionId must contain a single partition for create operations, found: "
							+ thePartitionIds);
		}
	}

	private static void logTroubleshootingResult(
			String theAction,
			String theResourceType,
			@Nullable RequestDetails theRequest,
			RequestPartitionId theResult) {
		String tenantId = theRequest != null ? theRequest.getTenantId() : null;
		ourLog.debug(
				"Partitioning: action={} resource type={} with request tenant ID={} routed to RequestPartitionId={}",
				theAction,
				theResourceType,
				tenantId,
				theResult);
	}

	private void logSystemRequestDetailsResolution(SystemRequestDetails theRequest) {
		ourLog.trace(
				"Partitioning: request is a SystemRequestDetails, with RequestPartitionId={}.",
				theRequest.getRequestPartitionId());
	}

	private static void logSubstitutingDefaultSystemRequestDetails() {
		ourLog.trace("No RequestDetails present.  Using default SystemRequestDetails.");
	}
}
