export function validateEmail(value, {
    requiredMessage = "Enter your email address.",
    invalidMessage = "Enter a valid email address.",
} = {}) {
    const email = value.trim();
    if (!email) return requiredMessage;
    if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return invalidMessage;
    return "";
}

export function validateAuthEmail(value) {
    return validateEmail(value, {
        requiredMessage: "Enter your email.",
        invalidMessage: "Enter a valid email.",
    });
}

export function validatePassword(value) {
    if (!value) return "Enter a password.";
    if (value.length < 12) return "Use at least 12 characters.";
    return "";
}

export function validateNewPassword({ password, confirmPassword }) {
    const errors = {};
    const passwordError = validatePassword(password);
    if (passwordError) errors.password = passwordError;
    const confirmationError = validatePasswordConfirmation(password, confirmPassword);
    if (confirmationError) errors.confirmPassword = confirmationError;
    return errors;
}

function validatePasswordConfirmation(password, confirmPassword) {
    if (!confirmPassword) return "Confirm your password.";
    if (password !== confirmPassword) return "Passwords don’t match.";
    return "";
}

export function validateRegistration(values) {
    const errors = {};
    if (!values.displayName.trim()) errors.fullName = "Enter your full name.";
    else if (values.displayName.trim().length > 120) errors.fullName = "Use no more than 120 characters.";
    const emailError = validateAuthEmail(values.email);
    if (emailError) errors.email = emailError;
    const passwordError = validatePassword(values.password);
    if (passwordError) errors.password = passwordError;
    const confirmationError = validatePasswordConfirmation(values.password, values.confirmPassword);
    if (confirmationError) errors.confirmPassword = confirmationError;
    if (!values.terms) errors.terms = "Accept the terms to continue.";
    return errors;
}

export function registrationErrorMessage(errors) {
    const missingMessages = {
        fullName: "Enter your full name.",
        email: "Enter your email.",
        password: "Enter a password.",
        confirmPassword: "Confirm your password.",
    };
    const missing = Object.keys(missingMessages).filter(key => errors[key] === missingMessages[key]);
    if (missing.length > 1) return "Complete all required fields.";
    if (missing.length === 1) return errors[missing[0]];
    return errors.fullName || errors.email || errors.password || errors.confirmPassword || errors.terms || "";
}

export function validateLogin({ email, password }) {
    const errors = {};
    const emailError = validateAuthEmail(email);
    if (emailError) errors.email = emailError;
    if (!password) errors.password = "Enter your password.";
    return errors;
}

export function loginErrorMessage(errors) {
    if (errors.email === "Enter your email." && errors.password === "Enter your password.") return "Complete all required fields.";
    if (errors.email === "Enter your email.") return errors.email;
    if (errors.password === "Enter your password.") return errors.password;
    return errors.email || errors.password || "";
}

export function focusFirstError(form, errors) {
    form.elements.namedItem(Object.keys(errors)[0])?.focus();
}
