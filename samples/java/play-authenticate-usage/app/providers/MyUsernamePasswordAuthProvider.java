package providers;

import com.feth.play.module.mail.Mailer.Mail.Body;
import com.feth.play.module.pa.PlayAuthenticate;
import com.feth.play.module.pa.providers.password.UsernamePasswordAuthProvider;
import com.feth.play.module.pa.providers.password.UsernamePasswordAuthUser;
import controllers.routes;
import models.LinkedAccount;
import models.TokenAction;
import models.TokenAction.Type;
import models.User;
import play.Application;
import play.Logger;
import play.data.Form;
import play.data.validation.Constraints.Email;
import play.data.validation.Constraints.MinLength;
import play.data.validation.Constraints.Required;
import play.i18n.Lang;
import play.i18n.Messages;
import play.mvc.Call;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Http.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MyUsernamePasswordAuthProvider
		extends
		UsernamePasswordAuthProvider<String, MyLoginUsernamePasswordAuthUser, MyUsernamePasswordAuthUser, MyUsernamePasswordAuthProvider.MyLogin, MyUsernamePasswordAuthProvider.MySignup> {

	private static final String SETTING_KEY_VERIFICATION_LINK_SECURE = SETTING_KEY_MAIL
			+ "." + "verificationLink.secure";
	private static final String SETTING_KEY_PASSWORD_RESET_LINK_SECURE = SETTING_KEY_MAIL
			+ "." + "passwordResetLink.secure";
	private static final String SETTING_KEY_LINK_LOGIN_AFTER_PASSWORD_RESET = "loginAfterPasswordReset";

	@Override
	protected List<String> neededSettingKeys() {
		final List<String> needed = new ArrayList<String>(
				super.neededSettingKeys());
		needed.add(SETTING_KEY_VERIFICATION_LINK_SECURE);
		needed.add(SETTING_KEY_PASSWORD_RESET_LINK_SECURE);
		needed.add(SETTING_KEY_LINK_LOGIN_AFTER_PASSWORD_RESET);
		return needed;
	}

	public static MyUsernamePasswordAuthProvider getProvider() {
		return (MyUsernamePasswordAuthProvider) PlayAuthenticate
				.getProvider(UsernamePasswordAuthProvider.PROVIDER_KEY);
	}

	public static class MyIdentity {

		public MyIdentity() {
		}

		public MyIdentity(final String email) {
			this.email = email;
		}

		@Required
		@Email
		public String email;

	}

	public static class MyLogin extends MyIdentity
			implements
			com.feth.play.module.pa.providers.password.UsernamePasswordAuthProvider.UsernamePassword {

		@Required
		@MinLength(5)
		public String password;

		@Override
		public String getEmail() {
			return email;
		}

		@Override
		public String getPassword() {
			return password;
		}
	}

	public static class MySignup extends MyLogin {

		@Required
		@MinLength(5)
		public String repeatPassword;

		@Required
		public String name;

		public String validate() {
			if (password == null || !password.equals(repeatPassword)) {
				return Messages
						.get("playauthenticate.password.signup.error.passwords_not_same");
			}
			return null;
		}
	}

	public static final Form<MySignup> SIGNUP_FORM = Controller
			.form(MySignup.class);
	public static final Form<MyLogin> LOGIN_FORM = Controller
			.form(MyLogin.class);

	public MyUsernamePasswordAuthProvider(Application app) {
		super(app);
	}

	protected Form<MySignup> getSignupForm() {
		return SIGNUP_FORM;
	}

	protected Form<MyLogin> getLoginForm() {
		return LOGIN_FORM;
	}

	@Override
	protected SignupResult signupUser(final MyUsernamePasswordAuthUser user) {
		final User u = User.findByUsernamePasswordIdentity(user);
		if (u != null) {
			if (u.emailValidated) {
				// This user exists, has its email validated and is active
				return SignupResult.USER_EXISTS;
			} else {
				// this user exists, is active but has not yet validated its
				// email
				return SignupResult.USER_EXISTS_UNVERIFIED;
			}
		}
		// The user either does not exist or is inactive - create a new one
		@SuppressWarnings("unused")
		final User newUser = User.create(user);
		// Usually the email should be verified before allowing login, however
		// if you return
		// return SignupResult.USER_CREATED;
		// then the user gets logged in directly
		return SignupResult.USER_CREATED_UNVERIFIED;
	}

	@Override
	protected LoginResult loginUser(
			final MyLoginUsernamePasswordAuthUser authUser) {
		final User u = User.findByUsernamePasswordIdentity(authUser);
		if (u == null) {
			return LoginResult.NOT_FOUND;
		} else {
			if (!u.emailValidated) {
				return LoginResult.USER_UNVERIFIED;
			} else {
				for (final LinkedAccount acc : u.linkedAccounts) {
					if (getKey().equals(acc.providerKey)) {
						if (authUser.checkPassword(acc.providerUserId,
								authUser.getPassword())) {
							// Password was correct
							return LoginResult.USER_LOGGED_IN;
						} else {
							// if you don't return here,
							// you would allow the user to have
							// multiple passwords defined
							// usually we don't want this
							return LoginResult.WRONG_PASSWORD;
						}
					}
				}
				return LoginResult.WRONG_PASSWORD;
			}
		}
	}

	@Override
	protected Call userExists(final UsernamePasswordAuthUser authUser) {
		return routes.Signup.exists();
	}

	@Override
	protected Call userUnverified(final UsernamePasswordAuthUser authUser) {
		return routes.Signup.unverified();
	}

	@Override
	protected MyUsernamePasswordAuthUser buildSignupAuthUser(
			final MySignup signup, final Context ctx) {
		return new MyUsernamePasswordAuthUser(signup);
	}

	@Override
	protected MyLoginUsernamePasswordAuthUser buildLoginAuthUser(
			final MyLogin login, final Context ctx) {
		return new MyLoginUsernamePasswordAuthUser(login.getPassword(),
				login.getEmail());
	}

	@Override
	protected String getVerifyEmailMailingSubject(
			final MyUsernamePasswordAuthUser user, final Context ctx) {
		return Messages.get("playauthenticate.password.verify_signup.subject");
	}

	@Override
	protected String onLoginUserNotFound(final Context context) {
		context.flash()
				.put(controllers.Application.FLASH_ERROR_KEY,
						Messages.get("playauthenticate.password.login.unknown_user_or_pw"));
		return super.onLoginUserNotFound(context);
	}

	@Override
	protected Body getVerifyEmailMailingBody(final String token,
			final MyUsernamePasswordAuthUser user, final Context ctx) {

        Class htmlClass = null;
        Method htmlRender = null;
        String html = null;

        Class textClass = null;
        Method textRender = null;
        String text = null;

        Lang lang = Lang.preferred(ctx.request().acceptLanguages());
        String langCode = lang.code();

		final boolean isSecure = getConfiguration().getBoolean(SETTING_KEY_VERIFICATION_LINK_SECURE);
		final String url = routes.Signup.verify(token).absoluteURL(ctx.request(), isSecure);


        // HTML version
        try {
            htmlClass = Class.forName("views.html.account.signup.email.verify_email_" + langCode);
        } catch (ClassNotFoundException e) {
            Logger.warn("Template: 'views.html.account.signup.email.verify_email_" + langCode + "' was not found! English template used instead.");
        }

        if (htmlClass == null) htmlClass = views.html.account.signup.email.verify_email_en.class;
        if (htmlClass != null) {
            try {
                htmlRender = htmlClass.getMethod("render", String.class, String.class, String.class);

            } catch (NoSuchMethodException  e) {
                e.printStackTrace();
            }
            try {
                html = htmlRender.invoke(null, url, token, user.getName()).toString();
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }

        // Text version
        try {
            textClass = Class.forName("views.txt.account.signup.email.verify_email_" + langCode);
        } catch (ClassNotFoundException e) {
            Logger.warn("Template: 'views.txt.account.signup.email.verify_email_" + langCode + "' was not found! English template used instead.");
        }

        if (textClass == null) textClass = views.txt.account.signup.email.verify_email_en.class;
        if (textClass != null) {
            try {
                textRender = textClass.getMethod("render", String.class, String.class, String.class);
            } catch (NoSuchMethodException  e) {
                e.printStackTrace();
            }
            try {
                text = textRender.invoke(null, url, token, user.getName()).toString();
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }

        return new Body(text, html);
	}

	private static String generateToken() {
		return UUID.randomUUID().toString();
	}

	@Override
	protected String generateVerificationRecord(
			final MyUsernamePasswordAuthUser user) {
		return generateVerificationRecord(User.findByAuthUserIdentity(user));
	}

	protected String generateVerificationRecord(final User user) {
		final String token = generateToken();
		// Do database actions, etc.
		TokenAction.create(Type.EMAIL_VERIFICATION, token, user);
		return token;
	}

	protected String generatePasswordResetRecord(final User u) {
		final String token = generateToken();
		TokenAction.create(Type.PASSWORD_RESET, token, u);
		return token;
	}

	protected String getPasswordResetMailingSubject(final User user,
			final Context ctx) {
		return Messages.get("playauthenticate.password.reset_email.subject");
	}

	protected Body getPasswordResetMailingBody(final String token,
			final User user, final Context ctx)  {

        Class htmlClass = null;
        Method htmlRender = null;
        String html = null;

        Class textClass = null;
        Method textRender = null;
        String text = null;

        Lang lang = Lang.preferred(ctx.request().acceptLanguages());
        String langCode = lang.code();


        final boolean isSecure = getConfiguration().getBoolean(SETTING_KEY_PASSWORD_RESET_LINK_SECURE);

        final String url = routes.Signup.resetPassword(token).absoluteURL(
                ctx.request(), isSecure);


        // HTML version
        try {
            htmlClass = Class.forName("views.html.account.email.password_reset_" + langCode);
        } catch (ClassNotFoundException e) {
            Logger.warn("Template: 'views.html.account.email.password_reset_" + langCode + "' was not found! English template used instead.");
        }
        if (htmlClass == null) htmlClass = views.html.account.email.password_reset_en.class;
        if (htmlClass != null) {
            try {
                htmlRender = htmlClass.getMethod("render", String.class, String.class, String.class);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
            try {
                html = htmlRender.invoke(null, url, token, user.name).toString();
            } catch (IllegalAccessException  e) {
                e.printStackTrace();
            } catch (InvocationTargetException e){
                e.printStackTrace();
            }

        }

        // Text version
        try {
            textClass = Class.forName("views.txt.account.email.password_reset_" + langCode);
        } catch (ClassNotFoundException e) {
            Logger.warn("Template: 'views.txt.account.email.password_reset_" + langCode + "' was not found! English template used instead.");
        }

        if (textClass == null) textClass = views.txt.account.email.password_reset_en.class;
        if (textClass != null) {
            try {
                textRender = textClass.getMethod("render", String.class, String.class, String.class);
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
            try {
                text = textRender.invoke(null, url, token, user.name).toString();
            } catch (IllegalAccessException  e) {
                e.printStackTrace();
            } catch (InvocationTargetException e){
                e.printStackTrace();
            }
        }
		return new Body(text, html);
	}

	public void sendPasswordResetMailing(final User user, final Context ctx) {
		final String token = generatePasswordResetRecord(user);
		final String subject = getPasswordResetMailingSubject(user, ctx);
		final Body body = getPasswordResetMailingBody(token, user, ctx);
		mailer.sendMail(subject, body, getEmailName(user));
	}

	public boolean isLoginAfterPasswordReset() {
		return getConfiguration().getBoolean(
				SETTING_KEY_LINK_LOGIN_AFTER_PASSWORD_RESET);
	}

	protected String getVerifyEmailMailingSubjectAfterSignup(final User user,
			final Context ctx) {
		return Messages.get("playauthenticate.password.verify_email.subject");
	}

	protected Body getVerifyEmailMailingBodyAfterSignup(final String token,
			final User user, final Context ctx)  {

        Class htmlClass = null;
        Method htmlRender = null;
        String html = null;

        Class textClass = null;
        Method textRender = null;
        String text = null;

        Lang lang = Lang.preferred(ctx.request().acceptLanguages());
        String langCode = lang.code();

		final boolean isSecure = getConfiguration().getBoolean(SETTING_KEY_VERIFICATION_LINK_SECURE);
		final String url = routes.Signup.verify(token).absoluteURL(ctx.request(), isSecure);

        // HTML version
        try {
            htmlClass = Class.forName("views.html.account.email.verify_email_" + langCode);
        } catch (ClassNotFoundException e) {
            Logger.warn("Template: 'views.html.account.email.verify_email_" + langCode + "' was not found! English template used instead.");
        }
        if (htmlClass == null) htmlClass = views.html.account.email.verify_email_en.class;

        if (htmlClass != null) {
            try {
                htmlRender = htmlClass.getMethod("render", String.class, String.class, String.class, String.class);
            } catch (NoSuchMethodException  e) {
                e.printStackTrace();
            }
            try {
                html = htmlRender.invoke(null, url, token, user.name, user.email).toString();
            } catch (IllegalAccessException  e) {
                e.printStackTrace();
            } catch (InvocationTargetException e){
                e.printStackTrace();
            }
        }

        // Text version
        try {
            textClass = Class.forName("views.txt.account.email.verify_email_" + langCode);
        } catch (ClassNotFoundException e) {
            Logger.warn("Template: 'views.txt.account.email.verify_email_" + langCode + "' was not found! English template used instead.");
        }
        if (textClass == null) textClass = views.txt.account.email.verify_email_en.class;

        if (textClass != null) {
            try {
                textRender = textClass.getMethod("render", String.class, String.class, String.class, String.class);
            } catch (NoSuchMethodException  e) {
                e.printStackTrace();
            }
            try {
                text = textRender.invoke(null, url, token, user.name, user.email).toString();
            } catch (IllegalAccessException  e) {
                e.printStackTrace();
            } catch (InvocationTargetException e){
                e.printStackTrace();
            }
        }

		return new Body(text, html);
	}



    public void sendVerifyEmailMailingAfterSignup(final User user,
			final Context ctx) {

		final String subject = getVerifyEmailMailingSubjectAfterSignup(user,
				ctx);
		final String token = generateVerificationRecord(user);
		final Body body = getVerifyEmailMailingBodyAfterSignup(token, user, ctx);
		mailer.sendMail(subject, body, getEmailName(user));
	}

	private String getEmailName(final User user) {
		return getEmailName(user.email, user.name);
	}
}
