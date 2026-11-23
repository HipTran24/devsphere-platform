import styles from "../styles/auth.module.css";
import formStyles from "../styles/AuthForm.module.css";

export default function AuthFlowShell({ children }) {
    return <main id="main-content" tabIndex={-1} className={styles.page}><section className={formStyles.flowCard}>{children}</section></main>;
}
