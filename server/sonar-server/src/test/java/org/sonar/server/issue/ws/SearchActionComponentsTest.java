/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.issue.ws;

import java.io.IOException;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.resources.Languages;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.Durations;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.issue.IssueTesting;
import org.sonar.db.organization.OrganizationDao;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.organization.OrganizationTesting;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleTesting;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.StartupIndexer;
import org.sonar.server.issue.ActionFinder;
import org.sonar.server.issue.IssueFieldsSetter;
import org.sonar.server.issue.IssueQueryFactory;
import org.sonar.server.issue.TransitionService;
import org.sonar.server.issue.index.IssueIndex;
import org.sonar.server.issue.index.IssueIndexDefinition;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.issue.index.IssueIteratorFactory;
import org.sonar.server.issue.workflow.FunctionExecutor;
import org.sonar.server.issue.workflow.IssueWorkflow;
import org.sonar.server.permission.index.AuthorizationTypeSupport;
import org.sonar.server.permission.index.PermissionIndexer;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.view.index.ViewDoc;
import org.sonar.server.view.index.ViewIndexDefinition;
import org.sonar.server.view.index.ViewIndexer;
import org.sonar.server.ws.WsActionTester;
import org.sonar.server.ws.WsResponseCommonFormat;
import org.sonarqube.ws.Issues;
import org.sonarqube.ws.Issues.SearchWsResponse;
import org.sonarqube.ws.client.issue.IssuesWsParameters;

import static com.google.common.collect.Lists.newArrayList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.api.utils.DateUtils.parseDateTime;
import static org.sonar.core.util.Uuids.UUID_EXAMPLE_01;
import static org.sonar.core.util.Uuids.UUID_EXAMPLE_02;
import static org.sonar.db.component.ComponentTesting.newFileDto;
import static org.sonar.db.component.ComponentTesting.newModuleDto;
import static org.sonar.db.component.SnapshotTesting.newAnalysis;

public class SearchActionComponentsTest {

  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();
  @Rule
  public DbTester dbTester = DbTester.create();
  @Rule
  public EsTester esTester = new EsTester(new IssueIndexDefinition(new MapSettings().asConfig()), new ViewIndexDefinition(new MapSettings().asConfig()));

  private DbClient db = dbTester.getDbClient();
  private DbSession session = dbTester.getSession();
  private IssueIndex issueIndex = new IssueIndex(esTester.client(), System2.INSTANCE, userSessionRule, new AuthorizationTypeSupport(userSessionRule));
  private IssueIndexer issueIndexer = new IssueIndexer(esTester.client(), db, new IssueIteratorFactory(db));
  private ViewIndexer viewIndexer = new ViewIndexer(db, esTester.client());
  private IssueQueryFactory issueQueryFactory = new IssueQueryFactory(db, System2.INSTANCE, userSessionRule);
  private IssueFieldsSetter issueFieldsSetter = new IssueFieldsSetter();
  private IssueWorkflow issueWorkflow = new IssueWorkflow(new FunctionExecutor(issueFieldsSetter), issueFieldsSetter);
  private SearchResponseLoader searchResponseLoader = new SearchResponseLoader(userSessionRule, db, new ActionFinder(userSessionRule), new TransitionService(userSessionRule, issueWorkflow));
  private Languages languages = new Languages();
  private SearchResponseFormat searchResponseFormat = new SearchResponseFormat(new Durations(), new WsResponseCommonFormat(languages), languages, new AvatarResolverImpl());
  private WsActionTester wsTester = new WsActionTester(new SearchAction(userSessionRule, issueIndex, issueQueryFactory, searchResponseLoader, searchResponseFormat));
  private OrganizationDto defaultOrganization;
  private OrganizationDto otherOrganization1;
  private OrganizationDto otherOrganization2;
  private StartupIndexer permissionIndexer = new PermissionIndexer(db, esTester.client(), issueIndexer);

  @Before
  public void setUp() {
    session = db.openSession(false);
    OrganizationDao organizationDao = db.organizationDao();
    this.defaultOrganization = dbTester.getDefaultOrganization();
    this.otherOrganization1 = OrganizationTesting.newOrganizationDto().setKey("my-org-1");
    this.otherOrganization2 = OrganizationTesting.newOrganizationDto().setKey("my-org-2");
    organizationDao.insert(session, this.otherOrganization1, false);
    organizationDao.insert(session, this.otherOrganization2, false);
    session.commit();
  }

  @After
  public void after() {
    session.close();
  }

  @Test
  public void issues_on_different_projects() throws Exception {
    RuleDto rule = newRule();
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    IssueDto issue = IssueTesting.newDto(rule, file, project)
      .setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setStatus("OPEN").setResolution("OPEN")
      .setSeverity("MAJOR")
      .setIssueCreationDate(DateUtils.parseDateTime("2014-09-04T00:00:00+0100"))
      .setIssueUpdateDate(DateUtils.parseDateTime("2017-12-04T00:00:00+0100"));
    db.issueDao().insert(session, issue);

    ComponentDto project2 = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "P2").setDbKey("PK2"));
    ComponentDto file2 = insertComponent(newFileDto(project2, null, "F2").setDbKey("FK2"));
    IssueDto issue2 = IssueTesting.newDto(rule, file2, project2)
      .setKee("92fd47d4-b650-4037-80bc-7b112bd4eac2")
      .setStatus("OPEN").setResolution("OPEN")
      .setSeverity("MAJOR")
      .setIssueCreationDate(DateUtils.parseDateTime("2014-09-04T00:00:00+0100"))
      .setIssueUpdateDate(DateUtils.parseDateTime("2017-12-04T00:00:00+0100"));
    db.issueDao().insert(session, issue2);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .execute()
      .assertJson(this.getClass(), "issues_on_different_projects.json");
  }

  @Test
  public void do_not_return_module_key_on_single_module_projects() throws IOException {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto module = insertComponent(newModuleDto("M1", project).setDbKey("MK1"));
    ComponentDto file = insertComponent(newFileDto(module, null, "F1").setDbKey("FK1"));
    RuleDto newRule = newRule();
    IssueDto issueInModule = IssueTesting.newDto(newRule, file, project).setKee("ISSUE_IN_MODULE");
    IssueDto issueInRootModule = IssueTesting.newDto(newRule, project, project).setKee("ISSUE_IN_ROOT_MODULE");
    db.issueDao().insert(session, issueInModule, issueInRootModule);
    session.commit();
    indexIssues();
    indexPermissions();

    SearchWsResponse searchResponse = wsTester.newRequest().executeProtobuf(SearchWsResponse.class);
    assertThat(searchResponse.getIssuesCount()).isEqualTo(2);

    for (Issues.Issue issue : searchResponse.getIssuesList()) {
      assertThat(issue.getProject()).isEqualTo("PK1");
      if (issue.getKey().equals("ISSUE_IN_MODULE")) {
        assertThat(issue.getSubProject()).isEqualTo("MK1");
      } else if (issue.getKey().equals("ISSUE_IN_ROOT_MODULE")) {
        assertThat(issue.hasSubProject()).isFalse();
      }
    }
  }

  @Test
  public void search_by_project_uuid() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_PROJECT_UUIDS, project.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_project_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_PROJECT_UUIDS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, project.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_project_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");
  }

  @Test
  public void search_since_leak_period_on_project() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    db.snapshotDao().insert(session,
      newAnalysis(project)
        .setPeriodDate(parseDateTime("2015-09-03T00:00:00+0100").getTime()));
    RuleDto rule = newRule();
    IssueDto issueAfterLeak = IssueTesting.newDto(rule, file, project)
      .setKee(UUID_EXAMPLE_01)
      .setIssueCreationDate(parseDateTime("2015-09-04T00:00:00+0100"))
      .setIssueUpdateDate(parseDateTime("2015-10-04T00:00:00+0100"));
    IssueDto issueBeforeLeak = IssueTesting.newDto(rule, file, project)
      .setKee(UUID_EXAMPLE_02)
      .setIssueCreationDate(parseDateTime("2014-09-04T00:00:00+0100"))
      .setIssueUpdateDate(parseDateTime("2015-10-04T00:00:00+0100"));
    db.issueDao().insert(session, issueAfterLeak, issueBeforeLeak);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, project.uuid())
      .setParam(IssuesWsParameters.PARAM_SINCE_LEAK_PERIOD, "true")
      .execute()
      .assertJson(this.getClass(), "search_since_leak_period.json");
  }

  @Test
  public void search_since_leak_period_on_file_in_module_project() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto module = insertComponent(newModuleDto(project));
    ComponentDto file = insertComponent(newFileDto(module, null, "F1").setDbKey("FK1"));
    db.snapshotDao().insert(session,
      newAnalysis(project).setPeriodDate(parseDateTime("2015-09-03T00:00:00+0100").getTime()));
    RuleDto rule = newRule();
    IssueDto issueAfterLeak = IssueTesting.newDto(rule, file, project)
      .setKee(UUID_EXAMPLE_01)
      .setIssueCreationDate(parseDateTime("2015-09-04T00:00:00+0100"))
      .setIssueUpdateDate(parseDateTime("2015-10-04T00:00:00+0100"));
    IssueDto issueBeforeLeak = IssueTesting.newDto(rule, file, project)
      .setKee(UUID_EXAMPLE_02)
      .setIssueCreationDate(parseDateTime("2014-09-04T00:00:00+0100"))
      .setIssueUpdateDate(parseDateTime("2015-10-04T00:00:00+0100"));
    db.issueDao().insert(session, issueAfterLeak, issueBeforeLeak);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, project.uuid())
      .setParam(IssuesWsParameters.PARAM_FILE_UUIDS, file.uuid())
      .setParam(IssuesWsParameters.PARAM_SINCE_LEAK_PERIOD, "true")
      .execute()
      .assertJson(this.getClass(), "search_since_leak_period.json");
  }

  @Test
  public void project_facet_is_sticky() throws Exception {
    ComponentDto project1 = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto project2 = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "P2").setDbKey("PK2"));
    ComponentDto project3 = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "P3").setDbKey("PK3"));
    ComponentDto file1 = insertComponent(newFileDto(project1, null, "F1").setDbKey("FK1"));
    ComponentDto file2 = insertComponent(newFileDto(project2, null, "F2").setDbKey("FK2"));
    ComponentDto file3 = insertComponent(newFileDto(project3, null, "F3").setDbKey("FK3"));
    RuleDto rule = newRule();
    IssueDto issue1 = IssueTesting.newDto(rule, file1, project1).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    IssueDto issue2 = IssueTesting.newDto(rule, file2, project2).setKee("2bd4eac2-b650-4037-80bc-7b1182fd47d4");
    IssueDto issue3 = IssueTesting.newDto(rule, file3, project3).setKee("7b1182fd-b650-4037-80bc-82fd47d4eac2");
    db.issueDao().insert(session, issue1, issue2, issue3);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_PROJECT_UUIDS, project1.uuid())
      .setParam(WebService.Param.FACETS, "projectUuids")
      .execute()
      .assertJson(this.getClass(), "display_sticky_project_facet.json");
  }

  @Test
  public void search_by_file_uuid() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_FILE_UUIDS, file.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_file_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_FILE_UUIDS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, file.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_file_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");
  }

  @Test
  public void search_by_file_key() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    ComponentDto unitTest = insertComponent(newFileDto(project, null, "F2").setQualifier(Qualifiers.UNIT_TEST_FILE).setDbKey("FK2"));
    RuleDto rule = newRule();
    IssueDto issueOnFile = IssueTesting.newDto(rule, file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    IssueDto issueOnTest = IssueTesting.newDto(rule, unitTest, project).setKee("2bd4eac2-b650-4037-80bc-7b1182fd47d4");
    db.issueDao().insert(session, issueOnFile, issueOnTest);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENTS, file.getDbKey())
      .execute()
      .assertJson(this.getClass(), "search_by_file_key.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENTS, unitTest.getDbKey())
      .execute()
      .assertJson(this.getClass(), "search_by_test_key.json");
  }

  @Test
  public void display_file_facet() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "P1").setDbKey("PK1"));
    ComponentDto file1 = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    ComponentDto file2 = insertComponent(newFileDto(project, null, "F2").setDbKey("FK2"));
    ComponentDto file3 = insertComponent(newFileDto(project, null, "F3").setDbKey("FK3"));
    RuleDto newRule = newRule();
    IssueDto issue1 = IssueTesting.newDto(newRule, file1, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    IssueDto issue2 = IssueTesting.newDto(newRule, file2, project).setKee("2bd4eac2-b650-4037-80bc-7b1182fd47d4");
    db.issueDao().insert(session, issue1, issue2);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, project.uuid())
      .setParam(IssuesWsParameters.PARAM_FILE_UUIDS, file1.uuid() + "," + file3.uuid())
      .setParam(WebService.Param.FACETS, "fileUuids")
      .execute()
      .assertJson(this.getClass(), "display_file_facet.json");
  }

  @Test
  public void search_by_directory_path() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto directory = insertComponent(ComponentTesting.newDirectory(project, "D1", "src/main/java/dir"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1").setPath(directory.path() + "/MyComponent.java"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, directory.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_file_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_DIRECTORIES, "src/main/java/dir")
      .execute()
      .assertJson(this.getClass(), "search_by_file_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_DIRECTORIES, "src/main/java")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");
  }

  @Test
  public void search_by_directory_path_in_different_modules() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "P1").setDbKey("PK1"));
    ComponentDto module1 = insertComponent(newModuleDto("M1", project).setDbKey("MK1"));
    ComponentDto module2 = insertComponent(newModuleDto("M2", project).setDbKey("MK2"));
    ComponentDto directory1 = insertComponent(ComponentTesting.newDirectory(module1, "D1", "src/main/java/dir"));
    ComponentDto directory2 = insertComponent(ComponentTesting.newDirectory(module2, "D2", "src/main/java/dir"));
    ComponentDto file1 = insertComponent(newFileDto(module1, directory1, "F1").setDbKey("FK1").setPath(directory1.path() + "/MyComponent.java"));
    insertComponent(newFileDto(module2, directory2, "F2").setDbKey("FK2").setPath(directory2.path() + "/MyComponent.java"));
    RuleDto rule = newRule();
    IssueDto issue1 = IssueTesting.newDto(rule, file1, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue1);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, directory1.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_directory_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, directory2.uuid())
      .execute()
      .assertJson(this.getClass(), "no_issue.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_MODULE_UUIDS, module1.uuid())
      .setParam(IssuesWsParameters.PARAM_DIRECTORIES, "src/main/java/dir")
      .execute()
      .assertJson(this.getClass(), "search_by_directory_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_MODULE_UUIDS, module2.uuid())
      .setParam(IssuesWsParameters.PARAM_DIRECTORIES, "src/main/java/dir")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_DIRECTORIES, "src/main/java/dir")
      .execute()
      .assertJson(this.getClass(), "search_by_directory_uuid.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_DIRECTORIES, "src/main/java")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");
  }

  @Test
  public void display_module_facet() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization2, "P1").setDbKey("PK1"));
    ComponentDto module = insertComponent(newModuleDto("M1", project).setDbKey("MK1"));
    ComponentDto subModule1 = insertComponent(newModuleDto("SUBM1", module).setDbKey("SUBMK1"));
    ComponentDto subModule2 = insertComponent(newModuleDto("SUBM2", module).setDbKey("SUBMK2"));
    ComponentDto subModule3 = insertComponent(newModuleDto("SUBM3", module).setDbKey("SUBMK3"));
    ComponentDto file1 = insertComponent(newFileDto(subModule1, null, "F1").setDbKey("FK1"));
    ComponentDto file2 = insertComponent(newFileDto(subModule2, null, "F2").setDbKey("FK2"));
    RuleDto newRule = newRule();
    IssueDto issue1 = IssueTesting.newDto(newRule, file1, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    IssueDto issue2 = IssueTesting.newDto(newRule, file2, project).setKee("2bd4eac2-b650-4037-80bc-7b1182fd47d4");
    db.issueDao().insert(session, issue1, issue2);
    session.commit();
    indexIssues();
    indexPermissions();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, module.uuid())
      .setParam(IssuesWsParameters.PARAM_MODULE_UUIDS, subModule1.uuid() + "," + subModule3.uuid())
      .setParam(WebService.Param.FACETS, "moduleUuids")
      .execute()
      .assertJson(this.getClass(), "display_module_facet.json");
  }

  @Test
  public void display_directory_facet() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto directory = insertComponent(ComponentTesting.newDirectory(project, "D1", "src/main/java/dir"));
    ComponentDto file = insertComponent(newFileDto(project, directory, "F1").setDbKey("FK1").setPath(directory.path() + "/MyComponent.java"));
    IssueDto issue = IssueTesting.newDto(newRule(), file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2");
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();
    indexPermissions();

    userSessionRule.logIn("john");
    wsTester.newRequest()
      .setParam("resolved", "false")
      .setParam(WebService.Param.FACETS, "directories")
      .execute()
      .assertJson(this.getClass(), "display_directory_facet.json");
  }

  @Test
  public void search_by_view_uuid() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(otherOrganization1, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    ComponentDto view = insertComponent(ComponentTesting.newView(defaultOrganization, "V1").setDbKey("MyView"));
    indexView(view.uuid(), newArrayList(project.uuid()));
    indexPermissions();

    insertIssue(IssueTesting.newDto(newRule(), file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2"));

    userSessionRule.logIn("john")
      .registerComponents(project, file, view);

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, view.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_view_uuid.json");
  }

  @Test
  public void search_by_sub_view_uuid() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    insertIssue(IssueTesting.newDto(newRule(), file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2"));

    ComponentDto view = insertComponent(ComponentTesting.newView(otherOrganization1, "V1").setDbKey("MyView"));
    indexView(view.uuid(), newArrayList(project.uuid()));
    ComponentDto subView = insertComponent(ComponentTesting.newSubView(view, "SV1", "MySubView"));
    indexView(subView.uuid(), newArrayList(project.uuid()));
    indexPermissions();

    userSessionRule.logIn("john")
      .registerComponents(project, file, view, subView);
    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, subView.uuid())
      .execute()
      .assertJson(this.getClass(), "search_by_view_uuid.json");
  }

  @Test
  public void search_by_sub_view_uuid_return_only_authorized_view() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    insertIssue(IssueTesting.newDto(newRule(), file, project).setKee("82fd47d4-b650-4037-80bc-7b112bd4eac2"));

    ComponentDto view = insertComponent(ComponentTesting.newView(otherOrganization1, "V1").setDbKey("MyView"));
    indexView(view.uuid(), newArrayList(project.uuid()));
    ComponentDto subView = insertComponent(ComponentTesting.newSubView(view, "SV1", "MySubView"));
    indexView(subView.uuid(), newArrayList(project.uuid()));

    // User has wrong permission on the view, no issue will be returned
    userSessionRule.logIn("john")
      .registerComponents(project, file, view, subView);

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_COMPONENT_UUIDS, subView.uuid())
      .execute()
      .assertJson(this.getClass(), "no_issue.json");
  }

  @Test
  public void search_by_author() throws Exception {
    ComponentDto project = insertComponent(ComponentTesting.newPublicProjectDto(defaultOrganization, "P1").setDbKey("PK1"));
    ComponentDto file = insertComponent(newFileDto(project, null, "F1").setDbKey("FK1"));
    RuleDto newRule = newRule();
    IssueDto issue1 = IssueTesting.newDto(newRule, file, project).setAuthorLogin("leia").setKee("2bd4eac2-b650-4037-80bc-7b112bd4eac2");
    IssueDto issue2 = IssueTesting.newDto(newRule, file, project).setAuthorLogin("luke@skywalker.name").setKee("82fd47d4-b650-4037-80bc-7b1182fd47d4");
    indexPermissions();

    db.issueDao().insert(session, issue1, issue2);
    session.commit();
    indexIssues();

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_AUTHORS, "leia")
      .setParam(WebService.Param.FACETS, "authors")
      .execute()
      .assertJson(this.getClass(), "search_by_authors.json");

    wsTester.newRequest()
      .setParam(IssuesWsParameters.PARAM_AUTHORS, "unknown")
      .execute()
      .assertJson(this.getClass(), "no_issue.json");

  }

  private RuleDto newRule() {
    RuleDto rule = RuleTesting.newXooX1()
      .setName("Rule name")
      .setDescription("Rule desc")
      .setStatus(RuleStatus.READY);
    dbTester.rules().insert(rule.getDefinition());
    session.commit();
    return rule;
  }

  private void indexPermissions() {
    permissionIndexer.indexOnStartup(permissionIndexer.getIndexTypes());
  }

  private IssueDto insertIssue(IssueDto issue) {
    db.issueDao().insert(session, issue);
    session.commit();
    indexIssues();
    return issue;
  }

  private ComponentDto insertComponent(ComponentDto component) {
    db.componentDao().insert(session, component);
    session.commit();
    return component;
  }

  private void indexIssues() {
    issueIndexer.indexOnStartup(issueIndexer.getIndexTypes());
  }

  private void indexView(String viewUuid, List<String> projects) {
    viewIndexer.index(new ViewDoc().setUuid(viewUuid).setProjects(projects));
  }
}
