/*
 * Copyright 2011 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.wicket.pages;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.wicket.PageParameters;
import org.apache.wicket.behavior.SimpleAttributeModifier;
import org.apache.wicket.extensions.markup.html.form.palette.Palette;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.PasswordTextField;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.util.CollectionModel;
import org.apache.wicket.model.util.ListModel;

import com.gitblit.Constants.RegistrantType;
import com.gitblit.GitBlit;
import com.gitblit.GitBlitException;
import com.gitblit.Keys;
import com.gitblit.models.RegistrantAccessPermission;
import com.gitblit.models.TeamModel;
import com.gitblit.models.UserModel;
import com.gitblit.utils.StringUtils;
import com.gitblit.wicket.RequiresAdminRole;
import com.gitblit.wicket.StringChoiceRenderer;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.panels.RegistrantPermissionsPanel;

@RequiresAdminRole
public class EditUserPage extends RootSubPage {

	private final boolean isCreate;
	
	public EditUserPage() {
		// create constructor
		super();
		if (!GitBlit.self().supportsCredentialChanges()) {
			error(MessageFormat.format(getString("gb.userServiceDoesNotPermitAddUser"),
					GitBlit.getString(Keys.realm.userService, "users.conf")), true);
		}
		isCreate = true;
		setupPage(new UserModel(""));
		setStatelessHint(false);
		setOutputMarkupId(true);
	}

	public EditUserPage(PageParameters params) {
		// edit constructor
		super(params);
		isCreate = false;
		String name = WicketUtils.getUsername(params);
		UserModel model = GitBlit.self().getUserModel(name);
		setupPage(model);
		setStatelessHint(false);
		setOutputMarkupId(true);
	}
	
	@Override
	protected boolean requiresPageMap() {
		return true;
	}

	protected void setupPage(final UserModel userModel) {
		if (isCreate) {
			super.setupPage(getString("gb.newUser"), "");
		} else {
			super.setupPage(getString("gb.edit"), userModel.username);
		}

		final Model<String> confirmPassword = new Model<String>(
				StringUtils.isEmpty(userModel.password) ? "" : userModel.password);
		CompoundPropertyModel<UserModel> model = new CompoundPropertyModel<UserModel>(userModel);

		// build list of projects including all repositories wildcards
		List<String> repos = getAccessRestrictedRepositoryList(true, userModel);
		
		List<String> userTeams = new ArrayList<String>();
		for (TeamModel team : userModel.teams) {
			userTeams.add(team.name);
		}
		Collections.sort(userTeams);
		
		final String oldName = userModel.username;
		final List<RegistrantAccessPermission> permissions = GitBlit.self().getUserAccessPermissions(userModel);

		final Palette<String> teams = new Palette<String>("teams", new ListModel<String>(
				new ArrayList<String>(userTeams)), new CollectionModel<String>(GitBlit.self()
				.getAllTeamnames()), new StringChoiceRenderer(), 10, false);
		Form<UserModel> form = new Form<UserModel>("editForm", model) {

			private static final long serialVersionUID = 1L;
			
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.apache.wicket.markup.html.form.Form#onSubmit()
			 */
			@Override
			protected void onSubmit() {
				if (StringUtils.isEmpty(userModel.username)) {
					error(getString("gb.pleaseSetUsername"));
					return;
				}
				// force username to lower-case
				userModel.username = userModel.username.toLowerCase();
				String username = userModel.username;
				if (isCreate) {
					UserModel model = GitBlit.self().getUserModel(username);
					if (model != null) {
						error(MessageFormat.format(getString("gb.usernameUnavailable"), username));
						return;
					}
				}
				boolean rename = !StringUtils.isEmpty(oldName)
						&& !oldName.equalsIgnoreCase(username);
				if (GitBlit.self().supportsCredentialChanges()) {
					if (!userModel.password.equals(confirmPassword.getObject())) {
						error(getString("gb.passwordsDoNotMatch"));
						return;
					}
					String password = userModel.password;
					if (!password.toUpperCase().startsWith(StringUtils.MD5_TYPE)
							&& !password.toUpperCase().startsWith(StringUtils.COMBINED_MD5_TYPE)) {
						// This is a plain text password.
						// Check length.
						int minLength = GitBlit.getInteger(Keys.realm.minPasswordLength, 5);
						if (minLength < 4) {
							minLength = 4;
						}
						if (password.trim().length() < minLength) {
							error(MessageFormat.format(getString("gb.passwordTooShort"),
									minLength));
							return;
						}
	
						// Optionally store the password MD5 digest.
						String type = GitBlit.getString(Keys.realm.passwordStorage, "md5");
						if (type.equalsIgnoreCase("md5")) {
							// store MD5 digest of password
							userModel.password = StringUtils.MD5_TYPE
									+ StringUtils.getMD5(userModel.password);
						} else if (type.equalsIgnoreCase("combined-md5")) {
							// store MD5 digest of username+password
							userModel.password = StringUtils.COMBINED_MD5_TYPE
									+ StringUtils.getMD5(username + userModel.password);
						}
					} else if (rename
							&& password.toUpperCase().startsWith(StringUtils.COMBINED_MD5_TYPE)) {
						error(getString("gb.combinedMd5Rename"));
						return;
					}
				}

				// update user permissions
				for (RegistrantAccessPermission repositoryPermission : permissions) {
					userModel.setRepositoryPermission(repositoryPermission.registrant, repositoryPermission.permission);
				}

				Iterator<String> selectedTeams = teams.getSelectedChoices();
				userModel.teams.clear();
				while (selectedTeams.hasNext()) {
					TeamModel team = GitBlit.self().getTeamModel(selectedTeams.next());
					if (team == null) {
						continue;
					}
					userModel.teams.add(team);
				}

				try {					
					GitBlit.self().updateUserModel(oldName, userModel, isCreate);
				} catch (GitBlitException e) {
					error(e.getMessage());
					return;
				}
				setRedirect(false);
				if (isCreate) {
					// create another user
					info(MessageFormat.format(getString("gb.userCreated"),
							userModel.username));
					setResponsePage(EditUserPage.class);
				} else {
					// back to users page
					setResponsePage(UsersPage.class);
				}
			}
		};
		
		// do not let the browser pre-populate these fields
		form.add(new SimpleAttributeModifier("autocomplete", "off"));
		
		// not all user services support manipulating username and password
		boolean editCredentials = GitBlit.self().supportsCredentialChanges();
		
		// not all user services support manipulating display name
		boolean editDisplayName = GitBlit.self().supportsDisplayNameChanges();

		// not all user services support manipulating email address
		boolean editEmailAddress = GitBlit.self().supportsEmailAddressChanges();

		// not all user services support manipulating team memberships
		boolean editTeams = GitBlit.self().supportsTeamMembershipChanges();

		// field names reflective match UserModel fields
		form.add(new TextField<String>("username").setEnabled(editCredentials));
		PasswordTextField passwordField = new PasswordTextField("password");
		passwordField.setResetPassword(false);
		form.add(passwordField.setEnabled(editCredentials));
		PasswordTextField confirmPasswordField = new PasswordTextField("confirmPassword",
				confirmPassword);
		confirmPasswordField.setResetPassword(false);
		form.add(confirmPasswordField.setEnabled(editCredentials));
		form.add(new TextField<String>("displayName").setEnabled(editDisplayName));
		form.add(new TextField<String>("emailAddress").setEnabled(editEmailAddress));
		form.add(new CheckBox("canAdmin"));
		form.add(new CheckBox("canFork"));
		form.add(new CheckBox("canCreate"));
		form.add(new CheckBox("excludeFromFederation"));
		form.add(new RegistrantPermissionsPanel("repositories",	RegistrantType.REPOSITORY, repos, permissions, getAccessPermissions()));
		form.add(teams.setEnabled(editTeams));

		form.add(new Button("save"));
		Button cancel = new Button("cancel") {
			private static final long serialVersionUID = 1L;

			@Override
			public void onSubmit() {
				setResponsePage(UsersPage.class);
			}
		};
		cancel.setDefaultFormProcessing(false);
		form.add(cancel);

		add(form);
	}
}
