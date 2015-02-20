/*******************************************************************************
 * Copyright (c) 2012, 2015 SAP SE and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP SE - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.p2.tools.publisher;

import static java.util.Arrays.asList;
import static org.eclipse.tycho.p2.testutil.InstallableUnitMatchers.configureTouchpointInstructionThat;
import static org.eclipse.tycho.p2.testutil.InstallableUnitMatchers.hasSelfCapability;
import static org.eclipse.tycho.p2.testutil.InstallableUnitMatchers.requirement;
import static org.eclipse.tycho.p2.testutil.InstallableUnitMatchers.strictRequirement;
import static org.eclipse.tycho.p2.testutil.InstallableUnitMatchers.unitWithId;
import static org.eclipse.tycho.p2.testutil.InstallableUnitUtil.createBundleIU;
import static org.eclipse.tycho.p2.testutil.InstallableUnitUtil.createFeatureIU;
import static org.eclipse.tycho.p2.testutil.MatchingItemFinder.getUnique;
import static org.eclipse.tycho.p2.tools.test.util.ResourceUtil.resourceFile;
import static org.eclipse.tycho.test.util.TychoMatchers.isFile;
import static org.hamcrest.CoreMatchers.both;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.tycho.artifacts.DependencyResolutionException;
import org.eclipse.tycho.core.resolver.shared.DependencySeed;
import org.eclipse.tycho.core.shared.BuildFailureException;
import org.eclipse.tycho.core.shared.Interpolator;
import org.eclipse.tycho.core.shared.TargetEnvironment;
import org.eclipse.tycho.p2.target.FinalTargetPlatformImpl;
import org.eclipse.tycho.p2.target.P2TargetPlatform;
import org.eclipse.tycho.p2.tools.publisher.facade.PublishProductTool;
import org.eclipse.tycho.repository.module.PublishingRepositoryImpl;
import org.eclipse.tycho.repository.publishing.PublishingRepository;
import org.eclipse.tycho.test.util.LogVerifier;
import org.eclipse.tycho.test.util.P2Context;
import org.eclipse.tycho.test.util.ReactorProjectIdentitiesStub;
import org.eclipse.tycho.test.util.TychoMatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class PublishProductToolTest {

    private static final String QUALIFIER = "20150109";
    private static final String FLAVOR = "tooling";
    private static final List<TargetEnvironment> ENVIRONMENTS = Collections.singletonList(new TargetEnvironment(
            "testos", "testws", "testarch"));
    private static final Set<String> ROOTS = Collections.emptySet();

    @Rule
    public ExpectedException exceptions = ExpectedException.none();
    @Rule
    public LogVerifier logVerifier = new LogVerifier();
    @Rule
    public TemporaryFolder tempManager = new TemporaryFolder();
    @Rule
    public P2Context p2Context = new P2Context();

    @Rule
    public ExpectedException thrownException = ExpectedException.none();

    private Interpolator interpolatorMock;

    private PublishingRepository outputRepository;
    private PublishProductTool subject;

    @Before
    public void before() throws Exception {
        File projectDirectory = tempManager.newFolder("projectDir");
        outputRepository = new PublishingRepositoryImpl(p2Context.getAgent(), new ReactorProjectIdentitiesStub(
                projectDirectory));

        interpolatorMock = mock(Interpolator.class);
        when(interpolatorMock.interpolate(anyString())).thenAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) throws Throwable {
                // by default don't expand anything
                return (String) invocation.getArguments()[0];
            }
        });
    }

    private PublishProductTool initPublisher(IInstallableUnit... tpUnits) {
        LinkedHashSet<IInstallableUnit> contextUnits = new LinkedHashSet<IInstallableUnit>();
        contextUnits.addAll(Arrays.asList(tpUnits));
        P2TargetPlatform targetPlatform = new FinalTargetPlatformImpl(contextUnits, null, null, null, null, null);

        PublisherActionRunner publisherRunner = new PublisherActionRunner(
                targetPlatform.getInstallableUnitsAsMetadataRepository(), ENVIRONMENTS, logVerifier.getLogger());
        return new PublishProductToolImpl(publisherRunner, outputRepository, targetPlatform, QUALIFIER,
                interpolatorMock, logVerifier.getLogger());
    }

    @Test
    public void testProductPublishingWithLaunchers() throws Exception {
        File productDefinition = resourceFile("publishers/products/test.product");
        File launcherBinaries = resourceFile("launchers/");

        subject = initPublisher();
        Collection<DependencySeed> seeds = subject.publishProduct(productDefinition, ROOTS, launcherBinaries, FLAVOR);

        assertThat(seeds.size(), is(1));
        DependencySeed seed = seeds.iterator().next();

        Set<Object> publishedUnits = outputRepository.getInstallableUnits();
        assertThat(publishedUnits, hasItem(seed.getInstallableUnit()));

        // test for launcher artifact
        Map<String, File> artifactLocations = outputRepository.getArtifactLocations();
        // TODO 348586 drop productUid from classifier
        String executableClassifier = "productUid.executable.testws.testos.testarch";
        assertThat(artifactLocations.keySet(), hasItem(executableClassifier));
        assertThat(artifactLocations.get(executableClassifier), isFile());
        assertThat(artifactLocations.get(executableClassifier).toString(), endsWith(".zip"));
    }

    @Test
    public void testExpandProductVersionQualifier() {
        File productDefinition = resourceFile("publishers/products/test.product");
        subject = initPublisher();

        IInstallableUnit unit = getUnit(subject.publishProduct(productDefinition, ROOTS, null, FLAVOR));

        assertThat(unit.getVersion().toString(), is("0.1.0." + QUALIFIER));
    }

    @Test
    public void testExpandVersionsOfInclusionsWithZeros() throws Exception {
        File productDefinition = resourceFile("publishers/products/inclusionsWithZeros.product");
        subject = initPublisher(createBundleIU("test.plugin", "0.1.0.20141230"),
                createBundleIU("test.plugin", "1.1.0"), createFeatureIU("test.feature", "0.2.0.20141230"),
                createFeatureIU("test.feature", "1.2.0"));

        IInstallableUnit unit = getUnit(subject.publishProduct(productDefinition, ROOTS, null, FLAVOR));

        assertThat(unit.getRequirements(), hasItem(strictRequirement("test.plugin", "1.1.0")));
        assertThat(unit.getRequirements(), hasItem(strictRequirement("test.feature.feature.group", "1.2.0")));
    }

    @Test
    public void testExpandVersionsOfInclusionsWithQualifierLiterals() throws Exception {
        File productDefinition = resourceFile("publishers/products/inclusionsWithQualifiers.product");
        subject = initPublisher(createBundleIU("test.plugin", "0.1.0.20141230"),
                createBundleIU("test.plugin", "1.1.0"), createFeatureIU("test.feature", "0.2.0.20141230"),
                createFeatureIU("test.feature", "1.2.0"));

        IInstallableUnit unit = getUnit(subject.publishProduct(productDefinition, ROOTS, null, FLAVOR));

        assertThat(unit.getRequirements(), hasItem(strictRequirement("test.plugin", "0.1.0.20141230")));
        assertThat(unit.getRequirements(), hasItem(strictRequirement("test.feature.feature.group", "0.2.0.20141230")));
    }

    @Test
    public void testExpandVersionWithSyntaxError() throws Exception {
        File productDefinition = resourceFile("publishers/products/inclusionsWithVersionSyntaxError.product");
        subject = initPublisher();

        exceptions.expect(BuildFailureException.class);
        exceptions.expectMessage(both(containsString("inclusionsWithVersionSyntaxError.product")).and(
                containsString("nonOSGi"))); // "nonOSGi" is the malformed version string
        subject.publishProduct(productDefinition, ROOTS, null, FLAVOR);
    }

    @Test
    public void testPublishingReportsAllResolutionErrorsAtOnce() throws Exception {
        File productDefinition = resourceFile("publishers/products/featureProduct.product");
        subject = initPublisher(createFeatureIU("test.feature1", "1.0.0")); // this version doesn't match

        logVerifier.expectError(containsString("test.feature1"));
        logVerifier.expectError(containsString("test.feature2"));
        thrownException.expect(DependencyResolutionException.class);
        subject.publishProduct(productDefinition, ROOTS, null, FLAVOR);
    }

    @Test
    public void testExpandVersionsIgnoresBundlesInFeatureBasedProduct() throws Exception {
        // product with useFeatures="true" and a non-resolvable plug-in reference -> the plug-in reference must be ignored (see bug 359090)
        File productDefinition = resourceFile("publishers/products/featureProductWithLeftovers.product");
        subject = initPublisher(createFeatureIU("org.eclipse.rcp", "3.3.101.R34x_v20081125"));

        IInstallableUnit unit = getUnit(subject.publishProduct(productDefinition, ROOTS, null, FLAVOR));

        assertThat(unit.getRequirements(),
                hasItem(strictRequirement("org.eclipse.rcp.feature.group", "3.3.101.R34x_v20081125")));
    }

    @Test
    public void testExpandVersionsIgnoresFeaturesInBundleBasedProduct() throws Exception {
        File productDefinition = resourceFile("publishers/products/pluginProductWithLeftovers.product");
        subject = initPublisher(createBundleIU("org.eclipse.core.runtime", "3.5.0.v20090525"));

        IInstallableUnit unit = getUnit(subject.publishProduct(productDefinition, ROOTS, null, FLAVOR));

        assertThat(unit.getRequirements(), hasItem(strictRequirement("org.eclipse.core.runtime", "3.5.0.v20090525")));
    }

    @Test
    public void testPublishingWithMissingFragments() throws Exception {
        // product referencing a fragment that is not in the target platform -> publisher must fail because the dependency resolution no longer detects this (see bug 342890)
        File productDefinition = resourceFile("publishers/products/missingFragment.product");
        File launcherBinaries = resourceFile("launchers/");
        subject = initPublisher(); // emtpy target platform

        logVerifier.expectError(containsString("org.eclipse.core.filesystem.hpux.ppc"));
        thrownException.expect(DependencyResolutionException.class);
        subject.publishProduct(productDefinition, ROOTS, launcherBinaries, FLAVOR);
    }

    @Test
    public void testPublishingWithP2Inf() {
        File productDefinition = resourceFile("publishers/products/p2Inf/test.product");
        subject = initPublisher();

        subject.publishProduct(productDefinition, ROOTS, null, FLAVOR);

        assertThat(unitsIn(outputRepository), hasItem(unitWithId("testproduct")));
        IInstallableUnit unit = getUnique(unitWithId("testproduct"), unitsIn(outputRepository));
        assertThat(unit.getRequirements(), hasItem(strictRequirement("extra.iu", "1.2.3." + QUALIFIER)));

        assertThat(unitsIn(outputRepository), hasItem(unitWithId("extra.iu")));
        IInstallableUnit extraUnit = getUnique(unitWithId("extra.iu"), unitsIn(outputRepository));
        assertThat(extraUnit.getVersion().toString(), is("1.2.3." + QUALIFIER));
        assertThat(extraUnit, hasSelfCapability());
    }

    @Test
    public void testPublishingWithProductSpecificP2Inf() {
        File productDefinition = resourceFile("publishers/products/p2InfPerProduct/test.product");
        subject = initPublisher();

        IInstallableUnit unit = getUnit(subject.publishProduct(productDefinition, ROOTS, null, FLAVOR));

        assertThat(unit.getRequirements(), hasItem(strictRequirement("extra.iu", "1.2.3." + QUALIFIER)));
    }

    @Test
    public void testPublishingWithVariableExpansion() {
        File productDefinition = resourceFile("publishers/products/properties.product");
        subject = initPublisher(createBundleIU("org.eclipse.osgi", "3.10.1.v20140909-1633"));
        when(interpolatorMock.interpolate("${unqualifiedVersion}.${buildQualifier}")).thenReturn("1.0.0.20150109");

        IInstallableUnit mainUnit = getUnit(subject.publishProduct(productDefinition, ROOTS, null, FLAVOR));

        String configUnitId = "tooling" + mainUnit.getId() + ".config.testws.testos.testarch";
        IInstallableUnit configUnit = getUnique(unitWithId(configUnitId), unitsIn(outputRepository));
        assertThat(
                configUnit.getTouchpointData(),
                hasItem(configureTouchpointInstructionThat(containsString("setProgramProperty(propName:eclipse.buildId,propValue:1.0.0.20150109)"))));
    }

    @Test
    public void testPublishingWithRootFeatures() {
        File productDefinition = resourceFile("publishers/products/rootFeatures.product");
        subject = initPublisher(createFeatureIU("org.eclipse.rcp", "4.4.0.v20140128"),
                createFeatureIU("org.eclipse.e4.rcp", "1.0"), createFeatureIU("org.eclipse.help", "2.0.102.v20140128"),
                createFeatureIU("org.eclipse.egit", "2.0"));

        // TODO 428889 this information shall come from the IProductDescriptor
        Set<String> rootFeatures = new HashSet<String>(asList("org.eclipse.help", "org.eclipse.egit"));
        IInstallableUnit unit = getUnit(subject.publishProduct(productDefinition, rootFeatures, null, FLAVOR));

        assertThat(unit.getRequirements(), hasItem(requirement("org.eclipse.rcp.feature.group", "4.4.0.v20140128")));
        assertThat(unit.getRequirements(), hasItem(requirement("org.eclipse.e4.rcp.feature.group", "1.0")));
        assertThat(unit.getRequirements(),
                not(hasItem(requirement("org.eclipse.help.feature.group", "2.0.102.v20140128"))));
        assertThat(unit.getRequirements(), not(hasItem(requirement("org.eclipse.egit.feature.group", "2.0"))));

        // TODO 428889 extract root feature seeds on this class loader side
    }

    private static IInstallableUnit getUnit(Collection<DependencySeed> seeds) {
        assertThat(seeds, TychoMatchers.hasSize(1));
        return (IInstallableUnit) seeds.iterator().next().getInstallableUnit();
    }

    private static Set<IInstallableUnit> unitsIn(PublishingRepository results) {
        return results.getMetadataRepository().query(QueryUtil.ALL_UNITS, null).toUnmodifiableSet();
    }

}