#!/usr/bin/env bash

# Administrative script messages. The selected value is stored in
# ui.default_language and is also used as the server-side fallback locale.

cloud_config_path="${cloud_config_path:-/opt/zimbra/conf/nextcloud-zimlet.properties}"

cloud_read_language() {
  local value=""
  if [[ -r "$cloud_config_path" ]]; then
    value="$(sed -n 's/^ui\.default_language=//p' "$cloud_config_path" | tail -n 1)"
  fi
  case "${value,,}" in
    fr) UI_LANGUAGE="fr" ;; en|en-us) UI_LANGUAGE="en-US" ;; es|es-es) UI_LANGUAGE="es" ;;
    es-ar) UI_LANGUAGE="es-AR" ;; it|it-it) UI_LANGUAGE="it" ;; de|de-de) UI_LANGUAGE="de" ;;
    pt|pt-pt) UI_LANGUAGE="pt-PT" ;; pt-br) UI_LANGUAGE="pt-BR" ;;
    hi|hi-in) UI_LANGUAGE="hi-IN" ;; ms|ms-my) UI_LANGUAGE="ms-MY" ;; ru|ru-ru) UI_LANGUAGE="ru-RU" ;; *) UI_LANGUAGE="fr" ;;
  esac
  export UI_LANGUAGE
}

cloud_select_language() {
  cloud_read_language
  local default_choice=1 choice
  case "$UI_LANGUAGE" in
    en-US) default_choice=2 ;; es) default_choice=3 ;; it) default_choice=4 ;; de) default_choice=5 ;;
    pt-PT) default_choice=6 ;; pt-BR) default_choice=7 ;; es-AR) default_choice=8 ;;
    hi-IN) default_choice=9 ;; ms-MY) default_choice=10 ;; ru-RU) default_choice=11 ;;
  esac
  echo "Langue / Language / Idioma / Lingua / Sprache / भाषा / Bahasa / Язык"
  echo "  1) Français (par défaut / default)"
  echo "  2) English (United States)"
  echo "  3) Español (España)"
  echo "  4) Italiano"
  echo "  5) Deutsch"
  echo "  6) Português (Portugal)"
  echo "  7) Português (Brasil)"
  echo "  8) Español (Argentina)"
  echo "  9) हिन्दी (भारत)"
  echo " 10) Bahasa Melayu (Malaysia)"
  echo " 11) Русский (Россия)"
  while true; do
    read -r -p "Choix / Choice [$default_choice] : " choice
    choice="${choice:-$default_choice}"
    case "$choice" in
      1) UI_LANGUAGE="fr"; break ;; 2) UI_LANGUAGE="en-US"; break ;;
      3) UI_LANGUAGE="es"; break ;; 4) UI_LANGUAGE="it"; break ;;
      5) UI_LANGUAGE="de"; break ;; 6) UI_LANGUAGE="pt-PT"; break ;;
      7) UI_LANGUAGE="pt-BR"; break ;; 8) UI_LANGUAGE="es-AR"; break ;;
      9) UI_LANGUAGE="hi-IN"; break ;; 10) UI_LANGUAGE="ms-MY"; break ;;
      11) UI_LANGUAGE="ru-RU"; break ;;
      *) echo "1–11" >&2 ;;
    esac
  done
  export UI_LANGUAGE
  echo
}

# Select which Zimbra web clients receive the connector. CLOUD_UI_MODE can be
# set to modern, classic or both for unattended deployments.
cloud_select_ui_mode() {
  local requested="${CLOUD_UI_MODE:-}" choice="" default_choice=3
  case "${CLOUD_UI_MODE_DEFAULT:-both}" in
    modern) default_choice=1 ;;
    classic) default_choice=2 ;;
    both) default_choice=3 ;;
  esac
  case "${requested,,}" in
    modern|classic|both) CLOUD_UI_MODE="${requested,,}"; export CLOUD_UI_MODE; return ;;
    "") ;;
    *) printf '%s\n' "CLOUD_UI_MODE must be modern, classic or both." >&2; return 1 ;;
  esac

  echo
  case "${UI_LANGUAGE:-fr}" in
    fr)
      echo "Quelle interface Zimbra faut-il installer ?"
      echo "  1) Modern uniquement"
      echo "  2) Classic uniquement"
      echo "  3) Modern et Classic (recommandé pour un serveur mixte)"
      ;;
    *)
      echo "Which Zimbra interface should be installed?"
      echo "  1) Modern only"
      echo "  2) Classic only"
      echo "  3) Modern and Classic (recommended for a mixed server)"
      ;;
  esac
  while true; do
    read -r -p "$(cloud_msg your_choice) [$default_choice] : " choice
    choice="${choice//$'\r'/}"
    choice="${choice#"${choice%%[![:space:]]*}"}"
    choice="${choice%"${choice##*[![:space:]]}"}"
    choice="${choice:-$default_choice}"
    case "$choice" in
      1) CLOUD_UI_MODE="modern"; break ;;
      2) CLOUD_UI_MODE="classic"; break ;;
      3) CLOUD_UI_MODE="both"; break ;;
      *) printf '%s\n' "1-3" >&2 ;;
    esac
  done
  export CLOUD_UI_MODE
}

cloud_msg_russian() {
  case "$1" in
    root_required) printf '%s\n' 'Запустите этот скрипт с sudo или от имени root.' ;;
    zimbra_missing) printf '%s\n' 'Ошибка: /opt/zimbra не существует. Запустите скрипт на сервере Zimbra.' ;;
    required_value) printf '%s\n' 'Это значение обязательно.' ;;
    https_required) printf '%s\n' 'Ошибка: %s должен начинаться с https://' ;;
    invalid_address) printf '%s\n' 'Ошибка: недопустимый адрес.' ;;
    config_title) printf '%s\n' 'Настройка Cloud Zimlet 3.2.0-beta.7 (Nextcloud + офисный сервер)' ;;
    zimbra_url) printf '%s\n' 'Общедоступный адрес Zimbra' ;;
    remote_backgrounds_prompt) printf '%s\n' 'Разрешить фоновые фотографии Unsplash? Изображения загружаются с внешнего сервиса.' ;;
    enabled) printf '%s\n' 'Включено' ;;
    disabled) printf '%s\n' 'Отключено (локальный градиент, рекомендуется для конфиденциальности)' ;;
    account_mode) printf '%s\n' 'Режим подключения Nextcloud:' ;;
    account_personal) printf '%s\n' 'Личный: каждый пользователь указывает свой сервер и учётные данные' ;;
    account_managed) printf '%s\n' 'Управляемый: Zimlet автоматически создаёт учётную запись на настроенном сервере Nextcloud' ;;
    your_choice) printf '%s\n' 'Ваш выбор' ;;
    choose_1_2) printf '%s\n' 'Выберите 1 или 2.' ;;
    managed_url) printf '%s\n' 'Адрес управляемого сервера Nextcloud' ;;
    service_account) printf '%s\n' 'Служебная учётная запись администратора Nextcloud' ;;
    keep_service_password) printf '%s\n' 'Пароль приложения служебной учётной записи (оставьте пустым, чтобы сохранить текущий): ' ;;
    service_password) printf '%s\n' 'Пароль приложения служебной учётной записи Nextcloud: ' ;;
    invalid_password) printf '%s\n' 'Ошибка: недопустимый пароль приложения.' ;;
    managed_group) printf '%s\n' 'Группа Nextcloud для новых учётных записей (необязательно)' ;;
    managed_quota) printf '%s\n' 'Квота на учётную запись (пусто = квота Nextcloud по умолчанию)' ;;
    managed_language) printf '%s\n' 'Язык новых учётных записей Nextcloud' ;;
    office_provider) printf '%s\n' 'Используемый офисный сервер:' ;;
    office_url) printf '%s\n' 'Общедоступный адрес сервера %s' ;;
    security_mode) printf '%s\n' 'Режим безопасности %s:' ;;
    jwt_recommended) printf '%s\n' 'JWT (рекомендуется для рабочей среды)' ;;
    no_jwt_test) printf '%s\n' 'Без JWT (только для изолированного теста)' ;;
    jwt_header) printf '%s\n' 'Имя заголовка JWT для %s' ;;
    invalid_jwt_header) printf '%s\n' 'Ошибка: недопустимое имя заголовка JWT.' ;;
    keep_jwt_secret) printf '%s\n' 'Секрет JWT %s (оставьте пустым, чтобы сохранить текущий): ' ;;
    jwt_secret) printf '%s\n' 'Секрет JWT %s (не менее 32 символов): ' ;;
    jwt_minimum) printf '%s\n' 'Секрет должен содержать не менее 32 символов.' ;;
    jwt_existing_minimum) printf '%s\n' 'Ошибка: существующий секрет JWT должен содержать не менее 24 символов.' ;;
    jwt_no_spaces) printf '%s\n' 'Ошибка: секрет JWT не должен содержать пробелы или переводы строк.' ;;
    no_jwt_warning) printf '%s\n' 'Предупреждение: обмен данными с %s не будет подписан JWT.' ;;
    config_saved) printf '%s\n' 'Конфигурация сохранена.' ;;
    selected_engine) printf '%s\n' 'Выбранный движок: %s (%s).' ;;
    managed_summary) printf '%s\n' 'Режим Nextcloud: управляемый. Каждый ненастроенный пользователь сможет автоматически активировать свою учётную запись.' ;;
    quota_summary) printf '%s\n' 'Будет использоваться квота, заданная в Nextcloud%s.' ;;
    personal_summary) printf '%s\n' 'Режим Nextcloud: личный. Пользователи вводят URL, имя пользователя и пароль приложения.' ;;
    zimbra_tools_missing) printf '%s\n' 'Ошибка: инструменты Zimbra не найдены.' ;;
    incomplete_package) printf '%s\n' 'Ошибка: неполный пакет (отсутствует ZIP Zimlet или серверный JAR).' ;;
    quarantine) printf '%s\n' 'Предыдущий модуль перемещён из активного пути: %s' ;;
    rollback) printf '%s\n' 'Автоматическое восстановление предыдущего серверного модуля…' ;;
    compatibility_build) printf '%s\n' 'Сборка модуля с точными библиотеками этого сервера Zimbra…' ;;
    build_failed) printf '%s\n' 'Ошибка: сборка совместимости не выполнена. Модуль не будет установлен, mailboxd не будет перезапущен.' ;;
    jar_missing) printf '%s\n' 'Ошибка: сборка не создала ожидаемый JAR.' ;;
    server_migration) printf '%s\n' 'Настройка или перенос офисного сервера…' ;;
    loading_extension) printf '%s\n' 'Безопасная загрузка серверного расширения…' ;;
    extension_failed) printf '%s\n' 'Ошибка: расширение не ответило. Оно будет удалено, чтобы сохранить работоспособность Zimbra.' ;;
    deploy_modern) printf '%s\n' 'Развёртывание интерфейса Modern…' ;;
    chat_cos_sync_failed) printf '%s\n' 'Ошибка: не удалось назначить модуль Chat тем же COS и учётным записям, что и Cloud.' ;;
    chat_cos_synced) printf '%s\n' 'Назначения Chat синхронизированы: обновлено %s из %s COS Cloud.' ;;
    chat_accounts_synced) printf '%s\n' 'Явные назначения Chat для учётных записей синхронизированы: обновлено %s из %s явных назначений Cloud.' ;;
    cache_warning) printf '%s\n' 'Предупреждение: глобальная очистка кэша не ответила. Установка остаётся активной; войдите в Zimbra снова.' ;;
    install_done) printf '%s\n' 'Установка 3.2.0-beta.7 завершена. mailboxd работает, расширение сообщает ожидаемую версию.' ;;
    reconnect) printf '%s\n' 'Войдите в выбранный веб-клиент Zimbra снова или нажмите Ctrl+F5, затем откройте Облако.' ;;
    data_kept) printf '%s\n' 'Конфигурация и зашифрованные профили сохранены.' ;;
    uninstall_cache_warning) printf '%s\n' 'Предупреждение: глобальная очистка кэша не ответила, но удаление завершено.' ;;
    uninstall_done) printf '%s\n' 'Удаление завершено. Перемещённые элементы можно восстановить.' ;;
    ui_tools_missing) printf '%s\n' 'Ошибка: инструменты Zimbra или ZIP интерфейса не найдены.' ;;
    remove_modern) printf '%s\n' 'Удаление предыдущего маршрута Modern…' ;;
    clean_modern) printf '%s\n' 'Чистое развёртывание интерфейса Modern 3.2.0-beta.7…' ;;
    zimlet_cache_warning) printf '%s\n' 'Предупреждение: кэш Zimlet не ответил. Закройте все окна браузера перед повторным входом.' ;;
    ui_repaired) printf '%s\n' 'Интерфейс 3.2.0-beta.7 развёрнут без изменения Java-расширения, конфигурации или профилей.' ;;
    private_window) printf '%s\n' 'Закройте все окна Zimbra, откройте новое приватное окно и войдите снова.' ;;
    report_root) printf '%s\n' 'Запустите этот отчёт с sudo или от имени root.' ;;
    storage_title) printf '%s\n' 'Отчёт о хранилище Cloud Zimlet' ;;
    encrypted_profiles) printf '%s\n' 'Зашифрованные данные профилей: %s (%s)' ;;
    temporary_files) printf '%s\n' 'Текущие временные файлы: %s' ;;
    module_backups) printf '%s\n' 'Резервные копии предыдущих модулей: %s (%s)' ;;
    invalid_account) printf '%s\n' 'Недопустимый адрес учётной записи.' ;;
    account_missing) printf '%s\n' 'Учётная запись Zimbra не найдена: %s' ;;
    profile_size) printf '%s\n' 'Профиль %s: %s байт' ;;
    profile_contents) printf '%s\n' 'Файл содержит не более трёх зашифрованных профилей Nextcloud и их офисных настроек, но не облачные файлы.' ;;
    profile_count) printf '%s\n' 'Количество пользовательских профилей: %s' ;;
    report_account_help) printf '%s\n' 'Для конкретной учётной записи: sudo ./storage-report.sh user@domain.tld' ;;
    no_cloud_cache) printf '%s\n' 'Просмотренные файлы и миниатюры не сохраняются в Zimbra.' ;;
    draft_quota) printf '%s\n' 'Вложение, добавленное в черновик или письмо, хранится в Zimbra и учитывается в квоте почтового ящика.' ;;
    jdk_missing) printf '%s\n' 'Ошибка: JDK Zimbra не найден.' ;;
    libraries_missing) printf '%s\n' 'Ошибка: библиотеки Zimbra отсутствуют в %s' ;;
    compile_jars_missing) printf '%s\n' 'Ошибка: в %s не найдено JAR-файлов для компиляции' ;;
    *) printf '%s\n' "$1" ;;
  esac
}

cloud_msg_extended() {
  local values field_number
  case "$1" in
    root_required) values='Execute este script com sudo ou como root.|Execute este script com sudo ou como root.|इस स्क्रिप्ट को sudo या root के रूप में चलाएँ।|Jalankan skrip ini dengan sudo atau sebagai root.' ;;
    zimbra_missing) values='Erro: /opt/zimbra não existe. Execute este script no servidor Zimbra.|Erro: /opt/zimbra não existe. Execute este script no servidor Zimbra.|त्रुटि: /opt/zimbra मौजूद नहीं है। यह स्क्रिप्ट Zimbra सर्वर पर चलाएँ।|Ralat: /opt/zimbra tiada. Jalankan skrip ini pada pelayan Zimbra.' ;;
    required_value) values='Este valor é obrigatório.|Este valor é obrigatório.|यह मान आवश्यक है।|Nilai ini diperlukan.' ;;
    https_required) values='Erro: %s deve começar por https://|Erro: %s deve começar com https://|त्रुटि: %s का आरंभ https:// से होना चाहिए|Ralat: %s mesti bermula dengan https://' ;;
    invalid_address) values='Erro: endereço inválido.|Erro: endereço inválido.|त्रुटि: अमान्य पता।|Ralat: alamat tidak sah.' ;;
    config_title) values='Configuração da Zimlet Cloud 3.2.0-beta.7 (Nextcloud + servidor de escritório)|Configuração da Zimlet Cloud 3.2.0-beta.7 (Nextcloud + servidor de escritório)|Cloud Zimlet 3.2.0-beta.7 कॉन्फ़िगरेशन (Nextcloud + ऑफ़िस सर्वर)|Konfigurasi Zimlet Cloud 3.2.0-beta.7 (Nextcloud + pelayan pejabat)' ;;
    zimbra_url) values='Endereço público do Zimbra|Endereço público do Zimbra|Zimbra का सार्वजनिक पता|Alamat awam Zimbra' ;;
    remote_backgrounds_prompt) values='Permitir fotografias de fundo do Unsplash? As imagens são carregadas de um serviço externo.|Permitir fotos de fundo do Unsplash? As imagens são carregadas de um serviço externo.|Unsplash पृष्ठभूमि फ़ोटो की अनुमति दें? चित्र बाहरी सेवा से लोड होते हैं।|Benarkan foto latar Unsplash? Imej dimuatkan daripada perkhidmatan luaran.' ;;
    enabled) values='Ativado|Ativado|चालू|Didayakan' ;;
    disabled) values='Desativado (gradiente local, recomendado para privacidade)|Desativado (gradiente local, recomendado para privacidade)|बंद (स्थानीय ग्रेडिएंट, गोपनीयता के लिए अनुशंसित)|Dilumpuhkan (kecerunan setempat, disyorkan untuk privasi)' ;;
    account_mode) values='Modo de ligação Nextcloud:|Modo de conexão Nextcloud:|Nextcloud कनेक्शन मोड:|Mod sambungan Nextcloud:' ;;
    account_personal) values='Pessoal: cada utilizador introduz o seu servidor e credenciais|Pessoal: cada usuário informa seu servidor e credenciais|व्यक्तिगत: प्रत्येक उपयोगकर्ता अपना सर्वर और लॉगिन जानकारी दर्ज करता है|Peribadi: setiap pengguna memasukkan pelayan dan maklumat log masuk sendiri' ;;
    account_managed) values='Gerido: a Zimlet cria automaticamente a conta num servidor Nextcloud configurado|Gerenciado: a Zimlet cria automaticamente a conta em um servidor Nextcloud configurado|प्रबंधित: Zimlet कॉन्फ़िगर किए गए Nextcloud सर्वर पर खाता अपने आप बनाती है|Terurus: Zimlet mencipta akaun secara automatik pada pelayan Nextcloud yang dikonfigurasi' ;;
    your_choice) values='A sua escolha|Sua escolha|आपका चयन|Pilihan anda' ;;
    choose_1_2) values='Escolha 1 ou 2.|Escolha 1 ou 2.|1 या 2 चुनें।|Pilih 1 atau 2.' ;;
    managed_url) values='Endereço do servidor Nextcloud gerido|Endereço do servidor Nextcloud gerenciado|प्रबंधित Nextcloud सर्वर का पता|Alamat pelayan Nextcloud terurus' ;;
    service_account) values='Conta de serviço administradora do Nextcloud|Conta de serviço administradora do Nextcloud|Nextcloud व्यवस्थापक सेवा खाता|Akaun perkhidmatan pentadbir Nextcloud' ;;
    keep_service_password) values='Palavra-passe de aplicação da conta de serviço (vazio para manter a atual): |Senha de aplicativo da conta de serviço (vazio para manter a atual): |सेवा खाते का ऐप पासवर्ड (वर्तमान पासवर्ड रखने के लिए खाली छोड़ें): |Kata laluan aplikasi akaun perkhidmatan (kosong untuk kekalkan yang semasa): ' ;;
    service_password) values='Palavra-passe de aplicação da conta de serviço Nextcloud: |Senha de aplicativo da conta de serviço Nextcloud: |Nextcloud सेवा खाते का ऐप पासवर्ड: |Kata laluan aplikasi akaun perkhidmatan Nextcloud: ' ;;
    invalid_password) values='Erro: palavra-passe de aplicação inválida.|Erro: senha de aplicativo inválida.|त्रुटि: अमान्य ऐप पासवर्ड।|Ralat: kata laluan aplikasi tidak sah.' ;;
    managed_group) values='Grupo Nextcloud para novas contas (opcional)|Grupo Nextcloud para novas contas (opcional)|नए खातों का Nextcloud समूह (वैकल्पिक)|Kumpulan Nextcloud untuk akaun baharu (pilihan)' ;;
    managed_quota) values='Quota por conta (vazio = quota predefinida do Nextcloud)|Cota por conta (vazio = cota padrão do Nextcloud)|प्रति खाता कोटा (खाली = Nextcloud डिफ़ॉल्ट)|Kuota setiap akaun (kosong = kuota lalai Nextcloud)' ;;
    managed_language) values='Idioma das novas contas Nextcloud|Idioma das novas contas Nextcloud|नए Nextcloud खातों की भाषा|Bahasa akaun Nextcloud baharu' ;;
    office_provider) values='Servidor de escritório a utilizar:|Servidor de escritório a usar:|उपयोग करने वाला ऑफ़िस सर्वर:|Pelayan pejabat untuk digunakan:' ;;
    office_url) values='Endereço público do servidor %s|Endereço público do servidor %s|%s सर्वर का सार्वजनिक पता|Alamat awam pelayan %s' ;;
    security_mode) values='Modo de segurança de %s:|Modo de segurança do %s:|%s सुरक्षा मोड:|Mod keselamatan %s:' ;;
    jwt_recommended) values='JWT (recomendado em produção)|JWT (recomendado em produção)|JWT (प्रोडक्शन के लिए अनुशंसित)|JWT (disyorkan untuk pengeluaran)' ;;
    no_jwt_test) values='Sem JWT (apenas para um teste isolado)|Sem JWT (apenas para um teste isolado)|JWT के बिना (केवल अलग परीक्षण के लिए)|Tanpa JWT (hanya untuk ujian terpencil)' ;;
    jwt_header) values='Nome do cabeçalho JWT de %s|Nome do cabeçalho JWT do %s|%s JWT हेडर का नाम|Nama pengepala JWT %s' ;;
    invalid_jwt_header) values='Erro: nome do cabeçalho JWT inválido.|Erro: nome do cabeçalho JWT inválido.|त्रुटि: अमान्य JWT हेडर नाम।|Ralat: nama pengepala JWT tidak sah.' ;;
    keep_jwt_secret) values='Segredo JWT de %s (vazio para manter o atual): |Segredo JWT do %s (vazio para manter o atual): |%s JWT सीक्रेट (वर्तमान रखने के लिए खाली छोड़ें): |Rahsia JWT %s (kosong untuk kekalkan yang semasa): ' ;;
    jwt_secret) values='Segredo JWT de %s (mínimo de 32 caracteres): |Segredo JWT do %s (mínimo de 32 caracteres): |%s JWT सीक्रेट (कम से कम 32 अक्षर): |Rahsia JWT %s (sekurang-kurangnya 32 aksara): ' ;;
    jwt_minimum) values='O segredo deve ter pelo menos 32 caracteres.|O segredo deve ter pelo menos 32 caracteres.|सीक्रेट में कम से कम 32 अक्षर होने चाहिए।|Rahsia mesti mengandungi sekurang-kurangnya 32 aksara.' ;;
    jwt_existing_minimum) values='Erro: o segredo JWT existente deve ter pelo menos 24 caracteres.|Erro: o segredo JWT existente deve ter pelo menos 24 caracteres.|त्रुटि: मौजूदा JWT सीक्रेट में कम से कम 24 अक्षर होने चाहिए।|Ralat: rahsia JWT sedia ada mesti mempunyai sekurang-kurangnya 24 aksara.' ;;
    jwt_no_spaces) values='Erro: o segredo JWT não pode conter espaços nem quebras de linha.|Erro: o segredo JWT não pode conter espaços nem quebras de linha.|त्रुटि: JWT सीक्रेट में स्पेस या लाइन ब्रेक नहीं होने चाहिए।|Ralat: rahsia JWT tidak boleh mengandungi ruang atau baris baharu.' ;;
    no_jwt_warning) values='Aviso: as comunicações com %s não serão assinadas com JWT.|Aviso: as comunicações com %s não serão assinadas com JWT.|चेतावनी: %s के साथ संचार JWT से हस्ताक्षरित नहीं होगा।|Amaran: komunikasi dengan %s tidak akan ditandatangani menggunakan JWT.' ;;
    config_saved) values='Configuração guardada.|Configuração salva.|कॉन्फ़िगरेशन सहेजा गया।|Konfigurasi disimpan.' ;;
    selected_engine) values='Motor selecionado: %s (%s).|Mecanismo selecionado: %s (%s).|चुना गया इंजन: %s (%s)।|Enjin dipilih: %s (%s).' ;;
    managed_summary) values='Modo Nextcloud: gerido. Cada utilizador ainda não configurado poderá ativar automaticamente a sua conta.|Modo Nextcloud: gerenciado. Cada usuário ainda não configurado poderá ativar sua conta automaticamente.|Nextcloud मोड: प्रबंधित। प्रत्येक अपुष्ट उपयोगकर्ता अपना खाता अपने आप सक्रिय कर सकेगा।|Mod Nextcloud: terurus. Setiap pengguna yang belum dikonfigurasi boleh mengaktifkan akaun secara automatik.' ;;
    quota_summary) values='A quota permanecerá a definida pelo Nextcloud%s.|A cota permanecerá a definida pelo Nextcloud%s.|कोटा Nextcloud द्वारा तय कोटा ही रहेगा%s।|Kuota akan kekal seperti yang ditetapkan oleh Nextcloud%s.' ;;
    personal_summary) values='Modo Nextcloud: pessoal. Os utilizadores introduzirão o URL, nome de utilizador e palavra-passe de aplicação.|Modo Nextcloud: pessoal. Os usuários informarão URL, usuário e senha de aplicativo.|Nextcloud मोड: व्यक्तिगत। उपयोगकर्ता URL, उपयोगकर्ता नाम और ऐप पासवर्ड दर्ज करेंगे।|Mod Nextcloud: peribadi. Pengguna akan memasukkan URL, nama pengguna dan kata laluan aplikasi.' ;;
    zimbra_tools_missing) values='Erro: as ferramentas Zimbra não foram encontradas.|Erro: as ferramentas Zimbra não foram encontradas.|त्रुटि: Zimbra उपकरण नहीं मिले।|Ralat: alat Zimbra tidak ditemui.' ;;
    incomplete_package) values='Erro: pacote incompleto (ZIP da Zimlet ou JAR do servidor em falta).|Erro: pacote incompleto (ZIP da Zimlet ou JAR do servidor ausente).|त्रुटि: अधूरा पैकेज (Zimlet ZIP या सर्वर JAR गायब)।|Ralat: pakej tidak lengkap (ZIP Zimlet atau JAR pelayan tiada).' ;;
    quarantine) values='A retirar o módulo anterior do caminho ativo: %s|Movendo o módulo anterior para fora do caminho ativo: %s|पिछले मॉड्यूल को सक्रिय पथ से हटाया जा रहा है: %s|Mengalihkan modul sebelumnya daripada laluan aktif: %s' ;;
    rollback) values='A restaurar automaticamente o módulo de servidor anterior…|Restaurando automaticamente o módulo de servidor anterior…|पिछला सर्वर मॉड्यूल अपने आप पुनर्स्थापित हो रहा है…|Memulihkan modul pelayan sebelumnya secara automatik…' ;;
    compatibility_build) values='A compilar o módulo com as bibliotecas exatas deste servidor Zimbra…|Compilando o módulo com as bibliotecas exatas deste servidor Zimbra…|इस Zimbra सर्वर की सटीक लाइब्रेरी के साथ मॉड्यूल बनाया जा रहा है…|Membina modul menggunakan pustaka tepat pelayan Zimbra ini…' ;;
    build_failed) values='Erro: a compilação de compatibilidade falhou. Nenhum módulo será instalado e o mailboxd não será reiniciado.|Erro: a compilação de compatibilidade falhou. Nenhum módulo será instalado e o mailboxd não será reiniciado.|त्रुटि: संगतता बिल्ड विफल। कोई मॉड्यूल इंस्टॉल नहीं होगा और mailboxd पुनः आरंभ नहीं होगा।|Ralat: binaan keserasian gagal. Tiada modul akan dipasang dan mailboxd tidak akan dimulakan semula.' ;;
    jar_missing) values='Erro: a compilação não produziu o JAR esperado.|Erro: a compilação não produziu o JAR esperado.|त्रुटि: बिल्ड ने अपेक्षित JAR नहीं बनाया।|Ralat: binaan tidak menghasilkan JAR yang dijangka.' ;;
    server_migration) values='A configurar ou migrar o servidor de escritório…|Configurando ou migrando o servidor de escritório…|ऑफ़िस सर्वर कॉन्फ़िगर या माइग्रेट हो रहा है…|Mengkonfigurasi atau memindahkan pelayan pejabat…' ;;
    loading_extension) values='A carregar a extensão do servidor em segurança…|Carregando a extensão do servidor com segurança…|सर्वर एक्सटेंशन सुरक्षित रूप से लोड हो रहा है…|Memuatkan sambungan pelayan dengan selamat…' ;;
    extension_failed) values='Erro: a extensão não respondeu. Será removida para manter o Zimbra operacional.|Erro: a extensão não respondeu. Ela será removida para manter o Zimbra operacional.|त्रुटि: एक्सटेंशन ने उत्तर नहीं दिया। Zimbra को चालू रखने के लिए इसे हटाया जाएगा।|Ralat: sambungan tidak bertindak balas. Ia akan dialih keluar supaya Zimbra kekal beroperasi.' ;;
    deploy_modern) values='A implementar a interface Modern…|Implantando a interface Modern…|Modern इंटरफ़ेस तैनात हो रहा है…|Menggunakan antara muka Modern…' ;;
    chat_cos_sync_failed) values='Erro: não foi possível atribuir o módulo Chat às mesmas COS e contas que o Cloud.|Erro: não foi possível atribuir o módulo Chat às mesmas COS e contas do Cloud.|त्रुटि: Chat मॉड्यूल को Cloud वाले COS और खातों में असाइन नहीं किया जा सका।|Ralat: modul Chat tidak dapat diberikan kepada COS dan akaun yang sama seperti Cloud.' ;;
    chat_cos_synced) values='Atribuições do Chat sincronizadas: %s de %s COS Cloud atualizadas.|Atribuições do Chat sincronizadas: %s de %s COS Cloud atualizadas.|Chat असाइनमेंट सिंक किए गए: %s में से %s Cloud COS अपडेट हुए।|Penetapan Chat disegerakkan: %s daripada %s COS Cloud dikemas kini.' ;;
    chat_accounts_synced) values='Atribuições Chat explícitas das contas sincronizadas: %s de %s atribuições Cloud explícitas atualizadas.|Atribuições Chat explícitas das contas sincronizadas: %s de %s atribuições Cloud explícitas atualizadas.|स्पष्ट खाता Chat असाइनमेंट सिंक किए गए: %s में से %s स्पष्ट Cloud असाइनमेंट अपडेट हुए।|Penetapan Chat akaun tersurat disegerakkan: %s daripada %s penetapan Cloud tersurat dikemas kini.' ;;
    cache_warning) values='Aviso: a limpeza global da cache não respondeu. A instalação continua ativa; volte a iniciar sessão no Zimbra.|Aviso: a limpeza global do cache não respondeu. A instalação continua ativa; entre novamente no Zimbra.|चेतावनी: वैश्विक कैश साफ़ करने का उत्तर नहीं मिला। इंस्टॉलेशन सक्रिय है; Zimbra में फिर साइन इन करें।|Amaran: pembersihan cache global tidak bertindak balas. Pemasangan kekal aktif; log masuk semula ke Zimbra.' ;;
    install_done) values='Instalação 3.2.0-beta.7 concluída. O mailboxd está operacional e a extensão indica a versão esperada.|Instalação 3.2.0-beta.7 concluída. O mailboxd está em execução e a extensão informa a versão esperada.|इंस्टॉलेशन 3.2.0-beta.7 पूर्ण। mailboxd चल रहा है और एक्सटेंशन अपेक्षित संस्करण बता रहा है।|Pemasangan 3.2.0-beta.7 selesai. mailboxd sedang berjalan dan sambungan melaporkan versi yang dijangka.' ;;
    reconnect) values='Volte a iniciar sessão no cliente Web Zimbra escolhido ou prima Ctrl+F5 e abra Cloud.|Entre novamente no cliente Web Zimbra escolhido ou pressione Ctrl+F5 e abra Cloud.|चुने गए Zimbra वेब क्लाइंट में फिर साइन इन करें या Ctrl+F5 दबाएँ, फिर Cloud खोलें।|Log masuk semula ke klien web Zimbra yang dipilih atau tekan Ctrl+F5, kemudian buka Cloud.' ;;
    data_kept) values='A configuração e os perfis cifrados são mantidos.|A configuração e os perfis criptografados são mantidos.|कॉन्फ़िगरेशन और एन्क्रिप्टेड प्रोफ़ाइल सुरक्षित रखे गए हैं।|Konfigurasi dan profil disulitkan dikekalkan.' ;;
    uninstall_cache_warning) values='Aviso: a limpeza global da cache não respondeu, mas a desinstalação terminou.|Aviso: a limpeza global do cache não respondeu, mas a desinstalação foi concluída.|चेतावनी: वैश्विक कैश साफ़ करने का उत्तर नहीं मिला, पर अनइंस्टॉल पूरा हो गया।|Amaran: pembersihan cache global tidak bertindak balas, tetapi penyahpasangan selesai.' ;;
    uninstall_done) values='Desinstalação concluída. Os elementos movidos continuam recuperáveis.|Desinstalação concluída. Os itens movidos continuam recuperáveis.|अनइंस्टॉल पूरा हुआ। हटाए गए आइटम अभी भी वापस पाए जा सकते हैं।|Penyahpasangan selesai. Item yang dialihkan masih boleh dipulihkan.' ;;
    ui_tools_missing) values='Erro: ferramentas Zimbra ou ZIP da interface não encontrados.|Erro: ferramentas Zimbra ou ZIP da interface não encontrados.|त्रुटि: Zimbra उपकरण या इंटरफ़ेस ZIP नहीं मिला।|Ralat: alat Zimbra atau ZIP antara muka tidak ditemui.' ;;
    remove_modern) values='A remover a rota Modern anterior…|Removendo a rota Modern anterior…|पिछला Modern रूट हटाया जा रहा है…|Mengalih keluar laluan Modern sebelumnya…' ;;
    clean_modern) values='Implementação limpa da interface Modern 3.2.0-beta.7…|Implantação limpa da interface Modern 3.2.0-beta.7…|Modern 3.2.0-beta.7 इंटरफ़ेस की साफ़ तैनाती…|Penggunaan bersih antara muka Modern 3.2.0-beta.7…' ;;
    zimlet_cache_warning) values='Aviso: a cache da Zimlet não respondeu. Feche todas as janelas do navegador antes de voltar a iniciar sessão.|Aviso: o cache da Zimlet não respondeu. Feche todas as janelas do navegador antes de entrar novamente.|चेतावनी: Zimlet कैश ने उत्तर नहीं दिया। फिर साइन इन करने से पहले सभी ब्राउज़र विंडो बंद करें।|Amaran: cache Zimlet tidak bertindak balas. Tutup semua tetingkap pelayar sebelum log masuk semula.' ;;
    ui_repaired) values='Interface 3.2.0-beta.7 implementada sem alterar a extensão Java, a configuração ou os perfis.|Interface 3.2.0-beta.7 implantada sem alterar a extensão Java, a configuração ou os perfis.|इंटरफ़ेस 3.2.0-beta.7 Java एक्सटेंशन, कॉन्फ़िगरेशन या प्रोफ़ाइल बदले बिना तैनात हुआ।|Antara muka 3.2.0-beta.7 digunakan tanpa mengubah sambungan Java, konfigurasi atau profil.' ;;
    private_window) values='Feche todas as janelas Zimbra, abra uma nova janela privada e volte a iniciar sessão.|Feche todas as janelas do Zimbra, abra uma nova janela anônima e entre novamente.|सभी Zimbra विंडो बंद करें, नई निजी विंडो खोलें और फिर साइन इन करें।|Tutup semua tetingkap Zimbra, buka tetingkap peribadi baharu dan log masuk semula.' ;;
    report_root) values='Execute este relatório com sudo ou como root.|Execute este relatório com sudo ou como root.|यह रिपोर्ट sudo या root के रूप में चलाएँ।|Jalankan laporan ini dengan sudo atau sebagai root.' ;;
    storage_title) values='Relatório de armazenamento da Zimlet Cloud|Relatório de armazenamento da Zimlet Cloud|Cloud Zimlet स्टोरेज रिपोर्ट|Laporan storan Zimlet Cloud' ;;
    encrypted_profiles) values='Dados cifrados dos perfis: %s (%s)|Dados criptografados dos perfis: %s (%s)|एन्क्रिप्टेड प्रोफ़ाइल डेटा: %s (%s)|Data profil disulitkan: %s (%s)' ;;
    temporary_files) values='Ficheiros temporários atuais: %s|Arquivos temporários atuais: %s|वर्तमान अस्थायी फ़ाइलें: %s|Fail sementara semasa: %s' ;;
    module_backups) values='Cópias de segurança de módulos anteriores: %s (%s)|Backups de módulos anteriores: %s (%s)|पिछले मॉड्यूल बैकअप: %s (%s)|Sandaran modul sebelumnya: %s (%s)' ;;
    invalid_account) values='Endereço de conta inválido.|Endereço de conta inválido.|अमान्य खाता पता।|Alamat akaun tidak sah.' ;;
    account_missing) values='Conta Zimbra não encontrada: %s|Conta Zimbra não encontrada: %s|Zimbra खाता नहीं मिला: %s|Akaun Zimbra tidak ditemui: %s' ;;
    profile_size) values='Perfil %s: %s bytes|Perfil %s: %s bytes|प्रोफ़ाइल %s: %s बाइट|Profil %s: %s bait' ;;
    profile_contents) values='Este ficheiro contém no máximo três perfis Nextcloud cifrados e as respetivas definições de escritório, não os ficheiros Cloud.|Este arquivo contém no máximo três perfis Nextcloud criptografados e suas configurações de escritório, não os arquivos Cloud.|इस फ़ाइल में अधिकतम तीन एन्क्रिप्टेड Nextcloud प्रोफ़ाइल और उनकी ऑफ़िस सेटिंग हैं, क्लाउड फ़ाइलें नहीं।|Fail ini mengandungi maksimum tiga profil Nextcloud yang disulitkan dan tetapan pejabatnya, bukan fail Awan.' ;;
    profile_count) values='Número de perfis de utilizador: %s|Número de perfis de usuário: %s|उपयोगकर्ता प्रोफ़ाइलों की संख्या: %s|Bilangan profil pengguna: %s' ;;
    report_account_help) values='Para uma conta específica: sudo ./storage-report.sh utilizador@dominio.tld|Para uma conta específica: sudo ./storage-report.sh usuario@dominio.tld|किसी खास खाते के लिए: sudo ./storage-report.sh usuario@dominio.tld|Untuk akaun tertentu: sudo ./storage-report.sh pengguna@domain.tld' ;;
    no_cloud_cache) values='Os ficheiros consultados e pré-visualizados não são guardados no Zimbra.|Os arquivos acessados e visualizados não são armazenados no Zimbra.|ब्राउज़ और पूर्वावलोकित फ़ाइलें Zimbra पर नहीं रखी जातीं।|Fail yang dilayari dan dipratonton tidak disimpan pada Zimbra.' ;;
    draft_quota) values='Um anexo adicionado a um rascunho ou mensagem é guardado pelo Zimbra e conta para a quota da caixa de correio.|Um anexo adicionado a um rascunho ou mensagem é armazenado pelo Zimbra e conta na cota da caixa postal.|ड्राफ़्ट या संदेश में जोड़ी गई अटैचमेंट Zimbra में संग्रहीत होती है और मेलबॉक्स कोटा में गिनी जाती है।|Lampiran yang ditambah pada draf atau mesej disimpan oleh Zimbra dan dikira dalam kuota peti mel.' ;;
    jdk_missing) values='Erro: o JDK do Zimbra não foi encontrado.|Erro: o JDK do Zimbra não foi encontrado.|त्रुटि: Zimbra JDK नहीं मिला।|Ralat: JDK Zimbra tidak ditemui.' ;;
    libraries_missing) values='Erro: as bibliotecas Zimbra não existem em %s|Erro: as bibliotecas Zimbra estão ausentes em %s|त्रुटि: %s में Zimbra लाइब्रेरी नहीं हैं|Ralat: pustaka Zimbra tiada daripada %s' ;;
    compile_jars_missing) values='Erro: nenhum JAR de compilação encontrado em %s|Erro: nenhum JAR de compilação encontrado em %s|त्रुटि: %s में कोई कम्पाइल JAR नहीं मिला|Ralat: tiada JAR kompilasi ditemui dalam %s' ;;
    *) values="$1|$1|$1|$1" ;;
  esac
  case "${UI_LANGUAGE:-pt-PT}" in pt-PT) field_number=1 ;; pt-BR) field_number=2 ;; hi-IN) field_number=3 ;; ms-MY) field_number=4 ;; *) field_number=1 ;; esac
  awk -F'|' -v field_number="$field_number" '{print $field_number}' <<<"$values"
}

cloud_msg() {
  local values field_number
  case "$1" in
    root_required) values='Lancez ce script avec sudo ou en root.|Run this script with sudo or as root.|Ejecute este script con sudo o como root.|Eseguire questo script con sudo o come root.|Führen Sie dieses Skript mit sudo oder als root aus.' ;;
    zimbra_missing) values='Erreur : /opt/zimbra est absent. Ce script doit être exécuté sur le serveur Zimbra.|Error: /opt/zimbra is missing. Run this script on the Zimbra server.|Error: falta /opt/zimbra. Ejecute este script en el servidor Zimbra.|Errore: /opt/zimbra non esiste. Eseguire lo script sul server Zimbra.|Fehler: /opt/zimbra fehlt. Führen Sie dieses Skript auf dem Zimbra-Server aus.' ;;
    required_value) values='Cette valeur est obligatoire.|This value is required.|Este valor es obligatorio.|Questo valore è obbligatorio.|Dieser Wert ist erforderlich.' ;;
    https_required) values='Erreur : %s doit commencer par https://|Error: %s must start with https://|Error: %s debe comenzar por https://|Errore: %s deve iniziare con https://|Fehler: %s muss mit https:// beginnen' ;;
    invalid_address) values='Erreur : adresse invalide.|Error: invalid address.|Error: dirección no válida.|Errore: indirizzo non valido.|Fehler: ungültige Adresse.' ;;
    config_title) values='Configuration de la Zimlet Cloud 3.2.0-beta.7 (Nextcloud + serveur bureautique)|Cloud Zimlet 3.2.0-beta.7 configuration (Nextcloud + office server)|Configuración de la Zimlet Cloud 3.2.0-beta.7 (Nextcloud + servidor ofimático)|Configurazione della Cloud Zimlet 3.2.0-beta.7 (Nextcloud + server office)|Konfiguration der Cloud-Zimlet 3.2.0-beta.7 (Nextcloud + Office-Server)' ;;
    zimbra_url) values='Adresse publique de Zimbra|Public Zimbra address|Dirección pública de Zimbra|Indirizzo pubblico di Zimbra|Öffentliche Zimbra-Adresse' ;;
    remote_backgrounds_prompt) values='Autoriser les photos d’arrière-plan Unsplash ? Les images sont chargées depuis un service externe.|Allow Unsplash background photos? Images are loaded from an external service.|¿Permitir fotos de fondo de Unsplash? Las imágenes se cargan desde un servicio externo.|Consentire le foto di sfondo di Unsplash? Le immagini vengono caricate da un servizio esterno.|Unsplash-Hintergrundfotos zulassen? Die Bilder werden von einem externen Dienst geladen.' ;;
    enabled) values='Activé|Enabled|Activado|Attivato|Aktiviert' ;;
    disabled) values='Désactivé (dégradé local, recommandé pour la confidentialité)|Disabled (local gradient, recommended for privacy)|Desactivado (degradado local, recomendado para la privacidad)|Disattivato (sfumatura locale, consigliato per la privacy)|Deaktiviert (lokaler Verlauf, für Datenschutz empfohlen)' ;;
    account_mode) values='Mode de connexion Nextcloud :|Nextcloud connection mode:|Modo de conexión Nextcloud:|Modalità di connessione Nextcloud:|Nextcloud-Verbindungsmodus:' ;;
    account_personal) values='Personnel : chaque utilisateur renseigne son propre serveur et ses identifiants|Personal: each user enters their own server and credentials|Personal: cada usuario introduce su propio servidor y credenciales|Personale: ogni utente inserisce il proprio server e le proprie credenziali|Persönlich: Jeder Benutzer gibt seinen eigenen Server und seine Zugangsdaten ein' ;;
    account_managed) values='Géré : la Zimlet crée automatiquement le compte sur un serveur Nextcloud imposé|Managed: the Zimlet automatically creates the account on a configured Nextcloud server|Gestionado: la Zimlet crea automáticamente la cuenta en un servidor Nextcloud configurado|Gestito: la Zimlet crea automaticamente l’account su un server Nextcloud configurato|Verwaltet: Die Zimlet erstellt das Konto automatisch auf einem konfigurierten Nextcloud-Server' ;;
    your_choice) values='Votre choix|Your choice|Su elección|Scelta|Ihre Auswahl' ;;
    choose_1_2) values='Choisissez 1 ou 2.|Choose 1 or 2.|Elija 1 o 2.|Scegliere 1 o 2.|Wählen Sie 1 oder 2.' ;;
    managed_url) values='Adresse du serveur Nextcloud géré|Managed Nextcloud server address|Dirección del servidor Nextcloud gestionado|Indirizzo del server Nextcloud gestito|Adresse des verwalteten Nextcloud-Servers' ;;
    service_account) values='Compte de service administrateur Nextcloud|Nextcloud administrator service account|Cuenta de servicio administradora de Nextcloud|Account di servizio amministratore Nextcloud|Nextcloud-Administrator-Dienstkonto' ;;
    keep_service_password) values='Mot de passe d’application du compte de service (laisser vide pour conserver l’actuel) : |Service account app password (leave empty to keep the current one): |Contraseña de aplicación de la cuenta de servicio (vacío para conservar la actual): |Password applicazione dell’account di servizio (vuota per mantenere quella attuale): |App-Passwort des Dienstkontos (leer lassen, um das aktuelle beizubehalten): ' ;;
    service_password) values='Mot de passe d’application du compte de service Nextcloud : |Nextcloud service account app password: |Contraseña de aplicación de la cuenta de servicio Nextcloud: |Password applicazione dell’account di servizio Nextcloud: |App-Passwort des Nextcloud-Dienstkontos: ' ;;
    invalid_password) values='Erreur : mot de passe d’application invalide.|Error: invalid app password.|Error: contraseña de aplicación no válida.|Errore: password applicazione non valida.|Fehler: ungültiges App-Passwort.' ;;
    managed_group) values='Groupe Nextcloud des nouveaux comptes (facultatif)|Nextcloud group for new accounts (optional)|Grupo Nextcloud de las cuentas nuevas (opcional)|Gruppo Nextcloud per i nuovi account (facoltativo)|Nextcloud-Gruppe für neue Konten (optional)' ;;
    managed_quota) values='Quota par compte (laisser vide = quota par défaut Nextcloud)|Quota per account (empty = Nextcloud default quota)|Cuota por cuenta (vacío = cuota predeterminada de Nextcloud)|Quota per account (vuoto = quota predefinita Nextcloud)|Kontingent pro Konto (leer = Nextcloud-Standardkontingent)' ;;
    managed_language) values='Langue des nouveaux comptes Nextcloud|Language for new Nextcloud accounts|Idioma de las nuevas cuentas Nextcloud|Lingua dei nuovi account Nextcloud|Sprache neuer Nextcloud-Konten' ;;
    office_provider) values='Serveur bureautique à utiliser :|Office server to use:|Servidor ofimático que se utilizará:|Server office da utilizzare:|Zu verwendender Office-Server:' ;;
    office_url) values='Adresse publique du serveur %s|Public %s server address|Dirección pública del servidor %s|Indirizzo pubblico del server %s|Öffentliche Adresse des %s-Servers' ;;
    security_mode) values='Mode de sécurité de %s :|%s security mode:|Modo de seguridad de %s:|Modalità di sicurezza di %s:|Sicherheitsmodus von %s:' ;;
    jwt_recommended) values='JWT (recommandé en production)|JWT (recommended in production)|JWT (recomendado en producción)|JWT (consigliato in produzione)|JWT (für den Produktivbetrieb empfohlen)' ;;
    no_jwt_test) values='Sans JWT (uniquement pour un test isolé)|Without JWT (only for an isolated test)|Sin JWT (solo para una prueba aislada)|Senza JWT (solo per un test isolato)|Ohne JWT (nur für einen isolierten Test)' ;;
    jwt_header) values='Nom de l’en-tête JWT %s|%s JWT header name|Nombre de la cabecera JWT de %s|Nome dell’header JWT di %s|Name des JWT-Headers für %s' ;;
    invalid_jwt_header) values='Erreur : nom d’en-tête JWT invalide.|Error: invalid JWT header name.|Error: nombre de cabecera JWT no válido.|Errore: nome dell’header JWT non valido.|Fehler: ungültiger JWT-Headername.' ;;
    keep_jwt_secret) values='Secret JWT %s (laisser vide pour conserver l’actuel) : |%s JWT secret (leave empty to keep the current one): |Secreto JWT de %s (vacío para conservar el actual): |Segreto JWT di %s (vuoto per mantenere quello attuale): |JWT-Geheimnis für %s (leer lassen, um das aktuelle beizubehalten): ' ;;
    jwt_secret) values='Secret JWT %s (32 caractères minimum) : |%s JWT secret (at least 32 characters): |Secreto JWT de %s (mínimo 32 caracteres): |Segreto JWT di %s (almeno 32 caratteri): |JWT-Geheimnis für %s (mindestens 32 Zeichen): ' ;;
    jwt_minimum) values='Le secret doit contenir au moins 32 caractères.|The secret must contain at least 32 characters.|El secreto debe contener al menos 32 caracteres.|Il segreto deve contenere almeno 32 caratteri.|Das Geheimnis muss mindestens 32 Zeichen enthalten.' ;;
    jwt_existing_minimum) values='Erreur : le secret JWT existant doit contenir au moins 24 caractères.|Error: the existing JWT secret must contain at least 24 characters.|Error: el secreto JWT existente debe contener al menos 24 caracteres.|Errore: il segreto JWT esistente deve contenere almeno 24 caratteri.|Fehler: Das vorhandene JWT-Geheimnis muss mindestens 24 Zeichen enthalten.' ;;
    jwt_no_spaces) values='Erreur : le secret JWT ne doit contenir ni espace ni retour à la ligne.|Error: the JWT secret must not contain spaces or line breaks.|Error: el secreto JWT no debe contener espacios ni saltos de línea.|Errore: il segreto JWT non deve contenere spazi o ritorni a capo.|Fehler: Das JWT-Geheimnis darf keine Leerzeichen oder Zeilenumbrüche enthalten.' ;;
    no_jwt_warning) values='Avertissement : les échanges avec %s ne seront pas signés par JWT.|Warning: exchanges with %s will not be signed with JWT.|Advertencia: los intercambios con %s no estarán firmados con JWT.|Avviso: le comunicazioni con %s non saranno firmate con JWT.|Warnung: Der Datenaustausch mit %s wird nicht per JWT signiert.' ;;
    config_saved) values='Configuration enregistrée.|Configuration saved.|Configuración guardada.|Configurazione salvata.|Konfiguration gespeichert.' ;;
    selected_engine) values='Moteur sélectionné : %s (%s).|Selected engine: %s (%s).|Motor seleccionado: %s (%s).|Motore selezionato: %s (%s).|Ausgewähltes Modul: %s (%s).' ;;
    managed_summary) values='Mode Nextcloud : géré. Chaque utilisateur non configuré pourra activer automatiquement son compte.|Nextcloud mode: managed. Each unconfigured user can automatically activate their account.|Modo Nextcloud: gestionado. Cada usuario no configurado podrá activar automáticamente su cuenta.|Modalità Nextcloud: gestita. Ogni utente non configurato potrà attivare automaticamente il proprio account.|Nextcloud-Modus: verwaltet. Jeder noch nicht konfigurierte Benutzer kann sein Konto automatisch aktivieren.' ;;
    quota_summary) values='Le quota restera celui défini par Nextcloud%s.|The quota will remain the one defined by Nextcloud%s.|La cuota seguirá siendo la definida por Nextcloud%s.|Il quota resterà quello definito da Nextcloud%s.|Das Kontingent bleibt das von Nextcloud festgelegte%s.' ;;
    personal_summary) values='Mode Nextcloud : personnel. Les utilisateurs saisiront leur URL, identifiant et mot de passe d’application.|Nextcloud mode: personal. Users will enter their URL, username and app password.|Modo Nextcloud: personal. Los usuarios introducirán su URL, usuario y contraseña de aplicación.|Modalità Nextcloud: personale. Gli utenti inseriranno URL, nome utente e password applicazione.|Nextcloud-Modus: persönlich. Benutzer geben URL, Benutzername und App-Passwort ein.' ;;
    zimbra_tools_missing) values='Erreur : les outils Zimbra sont introuvables.|Error: Zimbra tools were not found.|Error: no se encontraron las herramientas de Zimbra.|Errore: strumenti Zimbra non trovati.|Fehler: Zimbra-Werkzeuge wurden nicht gefunden.' ;;
    incomplete_package) values='Erreur : paquet incomplet (ZIP Zimlet ou JAR serveur manquant).|Error: incomplete package (Zimlet ZIP or server JAR missing).|Error: paquete incompleto (falta el ZIP de la Zimlet o el JAR del servidor).|Errore: pacchetto incompleto (ZIP della Zimlet o JAR server mancante).|Fehler: unvollständiges Paket (Zimlet-ZIP oder Server-JAR fehlt).' ;;
    quarantine) values='Mise hors du chemin actif de l’ancien module : %s|Moving the previous module out of the active path: %s|Retirada del módulo anterior de la ruta activa: %s|Spostamento del modulo precedente fuori dal percorso attivo: %s|Vorheriges Modul wird aus dem aktiven Pfad verschoben: %s' ;;
    rollback) values='Restauration automatique du module serveur précédent…|Automatically restoring the previous server module…|Restauración automática del módulo de servidor anterior…|Ripristino automatico del modulo server precedente…|Vorheriges Servermodul wird automatisch wiederhergestellt…' ;;
    compatibility_build) values='Compilation du module contre les bibliothèques exactes de ce serveur Zimbra…|Building the module against this Zimbra server’s exact libraries…|Compilación del módulo con las bibliotecas exactas de este servidor Zimbra…|Compilazione del modulo con le librerie esatte di questo server Zimbra…|Modul wird gegen die exakten Bibliotheken dieses Zimbra-Servers kompiliert…' ;;
    build_failed) values='Erreur : la compilation de compatibilité a échoué. Aucun module ne sera installé et mailboxd ne sera pas redémarré.|Error: compatibility build failed. No module will be installed and mailboxd will not be restarted.|Error: la compilación de compatibilidad ha fallado. No se instalará ningún módulo ni se reiniciará mailboxd.|Errore: compilazione di compatibilità non riuscita. Nessun modulo verrà installato e mailboxd non verrà riavviato.|Fehler: Kompatibilitäts-Build fehlgeschlagen. Es wird kein Modul installiert und mailboxd wird nicht neu gestartet.' ;;
    jar_missing) values='Erreur : la compilation n’a pas produit le JAR attendu.|Error: the build did not produce the expected JAR.|Error: la compilación no produjo el JAR esperado.|Errore: la compilazione non ha prodotto il JAR previsto.|Fehler: Der Build hat die erwartete JAR-Datei nicht erzeugt.' ;;
    server_migration) values='Configuration ou migration du serveur bureautique…|Configuring or migrating the office server…|Configuración o migración del servidor ofimático…|Configurazione o migrazione del server office…|Office-Server wird konfiguriert oder migriert…' ;;
    loading_extension) values='Chargement sécurisé de l’extension serveur…|Safely loading the server extension…|Carga segura de la extensión del servidor…|Caricamento sicuro dell’estensione server…|Servererweiterung wird sicher geladen…' ;;
    extension_failed) values='Erreur : l’extension n’a pas répondu. Elle va être retirée afin de préserver Zimbra.|Error: the extension did not respond. It will be removed to keep Zimbra operational.|Error: la extensión no respondió. Se retirará para mantener Zimbra operativo.|Errore: l’estensione non ha risposto. Verrà rimossa per mantenere Zimbra operativo.|Fehler: Die Erweiterung antwortet nicht. Sie wird entfernt, damit Zimbra betriebsbereit bleibt.' ;;
    deploy_modern) values='Déploiement de l’interface Modern…|Deploying the Modern interface…|Despliegue de la interfaz Modern…|Distribuzione dell’interfaccia Modern…|Modern-Oberfläche wird bereitgestellt…' ;;
    chat_cos_sync_failed) values='Erreur : impossible d’attribuer le module Chat aux mêmes COS et comptes que Cloud.|Error: the Chat module could not be assigned to the same COSes and accounts as Cloud.|Error: no se pudo asignar el módulo Chat a las mismas COS y cuentas que Cloud.|Errore: impossibile assegnare il modulo Chat alle stesse COS e agli stessi account di Cloud.|Fehler: Das Chat-Modul konnte nicht denselben COS und Konten wie Cloud zugewiesen werden.' ;;
    chat_cos_synced) values='Attributions Chat synchronisées : %s COS Cloud mises à jour sur %s.|Chat assignments synchronized: %s of %s Cloud COSes updated.|Asignaciones de Chat sincronizadas: se actualizaron %s de %s COS de Cloud.|Assegnazioni Chat sincronizzate: aggiornate %s COS Cloud su %s.|Chat-Zuweisungen synchronisiert: %s von %s Cloud-COS aktualisiert.' ;;
    chat_accounts_synced) values='Attributions Chat explicites des comptes synchronisées : %s mises à jour sur %s attributions Cloud explicites.|Explicit account Chat assignments synchronized: %s of %s explicit Cloud assignments updated.|Asignaciones explícitas de Chat de las cuentas sincronizadas: se actualizaron %s de %s asignaciones explícitas de Cloud.|Assegnazioni Chat esplicite degli account sincronizzate: aggiornate %s assegnazioni Cloud esplicite su %s.|Explizite Chat-Kontenzuweisungen synchronisiert: %s von %s expliziten Cloud-Zuweisungen aktualisiert.' ;;
    cache_warning) values='Avertissement : le vidage global du cache n’a pas répondu. L’installation reste active ; reconnectez-vous à Zimbra.|Warning: the global cache flush did not respond. The installation remains active; sign in to Zimbra again.|Advertencia: el vaciado global de caché no respondió. La instalación sigue activa; vuelva a iniciar sesión en Zimbra.|Avviso: lo svuotamento globale della cache non ha risposto. L’installazione resta attiva; accedere nuovamente a Zimbra.|Warnung: Das globale Leeren des Caches antwortete nicht. Die Installation bleibt aktiv; melden Sie sich erneut bei Zimbra an.' ;;
    install_done) values='Installation 3.2.0-beta.7 terminée. mailboxd est opérationnel et l’extension répond avec la version attendue.|Installation 3.2.0-beta.7 completed. mailboxd is running and the extension reports the expected version.|Instalación 3.2.0-beta.7 terminada. mailboxd está operativo y la extensión indica la versión esperada.|Installazione 3.2.0-beta.7 completata. mailboxd è operativo e l’estensione restituisce la versione prevista.|Installation 3.2.0-beta.7 abgeschlossen. mailboxd läuft und die Erweiterung meldet die erwartete Version.' ;;
    reconnect) values='Reconnectez-vous dans le client Web Zimbra choisi ou faites Ctrl+F5, puis ouvrez Cloud.|Sign in to the selected Zimbra web client again or press Ctrl+F5, then open Cloud.|Vuelva a iniciar sesión en el cliente web de Zimbra elegido o pulse Ctrl+F5 y abra Cloud.|Accedere nuovamente al client Web Zimbra selezionato o premere Ctrl+F5, quindi aprire Cloud.|Melden Sie sich erneut im gewählten Zimbra-Webclient an oder drücken Sie Strg+F5 und öffnen Sie dann Cloud.' ;;
    data_kept) values='La configuration et les profils chiffrés sont conservés.|The configuration and encrypted profiles are preserved.|Se conservan la configuración y los perfiles cifrados.|La configurazione e i profili cifrati vengono conservati.|Konfiguration und verschlüsselte Profile bleiben erhalten.' ;;
    uninstall_cache_warning) values='Avertissement : le vidage global du cache n’a pas répondu, mais la désinstallation est terminée.|Warning: the global cache flush did not respond, but uninstallation is complete.|Advertencia: el vaciado global de caché no respondió, pero la desinstalación ha terminado.|Avviso: lo svuotamento globale della cache non ha risposto, ma la disinstallazione è terminata.|Warnung: Das globale Leeren des Caches antwortete nicht, die Deinstallation ist jedoch abgeschlossen.' ;;
    uninstall_done) values='Désinstallation terminée. Les éléments déplacés restent récupérables.|Uninstallation complete. Moved items remain recoverable.|Desinstalación terminada. Los elementos movidos siguen siendo recuperables.|Disinstallazione completata. Gli elementi spostati restano recuperabili.|Deinstallation abgeschlossen. Verschobene Elemente können weiterhin wiederhergestellt werden.' ;;
    ui_tools_missing) values='Erreur : outils Zimbra ou ZIP de l’interface introuvables.|Error: Zimbra tools or interface ZIP not found.|Error: no se encontraron las herramientas de Zimbra o el ZIP de la interfaz.|Errore: strumenti Zimbra o ZIP dell’interfaccia non trovati.|Fehler: Zimbra-Werkzeuge oder Oberflächen-ZIP wurden nicht gefunden.' ;;
    remove_modern) values='Retrait de l’ancienne route Modern…|Removing the previous Modern route…|Retirada de la ruta Modern anterior…|Rimozione della precedente route Modern…|Vorherige Modern-Route wird entfernt…' ;;
    clean_modern) values='Déploiement propre de l’interface Modern 3.2.0-beta.7…|Clean deployment of the Modern 3.2.0-beta.7 interface…|Despliegue limpio de la interfaz Modern 3.2.0-beta.7…|Distribuzione pulita dell’interfaccia Modern 3.2.0-beta.7…|Saubere Bereitstellung der Modern-Oberfläche 3.2.0-beta.7…' ;;
    zimlet_cache_warning) values='Avertissement : le cache Zimlet n’a pas répondu. Fermez tout de même toutes les fenêtres du navigateur avant de vous reconnecter.|Warning: the Zimlet cache did not respond. Close all browser windows before signing in again.|Advertencia: la caché de Zimlet no respondió. Cierre todas las ventanas del navegador antes de volver a iniciar sesión.|Avviso: la cache Zimlet non ha risposto. Chiudere tutte le finestre del browser prima di accedere nuovamente.|Warnung: Der Zimlet-Cache antwortete nicht. Schließen Sie vor der erneuten Anmeldung alle Browserfenster.' ;;
    ui_repaired) values='Interface 3.2.0-beta.7 déployée sans modifier l’extension Java, la configuration ou les profils.|Interface 3.2.0-beta.7 deployed without changing the Java extension, configuration or profiles.|Interfaz 3.2.0-beta.7 desplegada sin modificar la extensión Java, la configuración ni los perfiles.|Interfaccia 3.2.0-beta.7 distribuita senza modificare l’estensione Java, la configurazione o i profili.|Oberfläche 3.2.0-beta.7 bereitgestellt, ohne Java-Erweiterung, Konfiguration oder Profile zu ändern.' ;;
    private_window) values='Fermez toutes les fenêtres Zimbra, ouvrez une nouvelle fenêtre privée et reconnectez-vous.|Close all Zimbra windows, open a new private window and sign in again.|Cierre todas las ventanas de Zimbra, abra una nueva ventana privada y vuelva a iniciar sesión.|Chiudere tutte le finestre Zimbra, aprire una nuova finestra privata e accedere nuovamente.|Schließen Sie alle Zimbra-Fenster, öffnen Sie ein neues privates Fenster und melden Sie sich erneut an.' ;;
    report_root) values='Lancez ce rapport avec sudo ou en root.|Run this report with sudo or as root.|Ejecute este informe con sudo o como root.|Eseguire questo rapporto con sudo o come root.|Führen Sie diesen Bericht mit sudo oder als root aus.' ;;
    storage_title) values='Rapport de stockage de la Zimlet Cloud|Cloud Zimlet storage report|Informe de almacenamiento de la Zimlet Cloud|Rapporto di archiviazione della Zimlet Cloud|Speicherbericht der Cloud-Zimlet' ;;
    encrypted_profiles) values='Données chiffrées des profils : %s (%s)|Encrypted profile data: %s (%s)|Datos cifrados de perfiles: %s (%s)|Dati cifrati dei profili: %s (%s)|Verschlüsselte Profildaten: %s (%s)' ;;
    temporary_files) values='Fichiers temporaires actuels : %s|Current temporary files: %s|Archivos temporales actuales: %s|File temporanei attuali: %s|Aktuelle temporäre Dateien: %s' ;;
    module_backups) values='Sauvegardes des anciens modules : %s (%s)|Previous module backups: %s (%s)|Copias de seguridad de módulos anteriores: %s (%s)|Backup dei moduli precedenti: %s (%s)|Sicherungen früherer Module: %s (%s)' ;;
    invalid_account) values='Adresse de compte invalide.|Invalid account address.|Dirección de cuenta no válida.|Indirizzo account non valido.|Ungültige Kontoadresse.' ;;
    account_missing) values='Compte Zimbra introuvable : %s|Zimbra account not found: %s|Cuenta Zimbra no encontrada: %s|Account Zimbra non trovato: %s|Zimbra-Konto nicht gefunden: %s' ;;
    profile_size) values='Profil %s : %s octets|Profile %s: %s bytes|Perfil %s: %s bytes|Profilo %s: %s byte|Profil %s: %s Byte' ;;
    profile_contents) values='Ce fichier contient au maximum trois profils Nextcloud chiffrés et leurs réglages bureautiques, pas les fichiers Cloud.|This file contains at most three encrypted Nextcloud profiles and their office settings, not Cloud files.|Este archivo contiene como máximo tres perfiles Nextcloud cifrados y sus ajustes ofimáticos, no los archivos Cloud.|Questo file contiene al massimo tre profili Nextcloud cifrati e le relative impostazioni office, non i file Cloud.|Diese Datei enthält höchstens drei verschlüsselte Nextcloud-Profile und deren Office-Einstellungen, keine Cloud-Dateien.' ;;
    profile_count) values='Nombre de profils utilisateurs : %s|Number of user profiles: %s|Número de perfiles de usuario: %s|Numero di profili utente: %s|Anzahl der Benutzerprofile: %s' ;;
    report_account_help) values='Pour un compte précis : sudo ./storage-report.sh utilisateur@domaine.tld|For a specific account: sudo ./storage-report.sh user@domain.tld|Para una cuenta concreta: sudo ./storage-report.sh usuario@dominio.tld|Per un account specifico: sudo ./storage-report.sh utente@dominio.tld|Für ein bestimmtes Konto: sudo ./storage-report.sh benutzer@domain.tld' ;;
    no_cloud_cache) values='Les fichiers parcourus et prévisualisés ne sont pas conservés sur Zimbra.|Browsed and previewed files are not kept on Zimbra.|Los archivos explorados y previsualizados no se conservan en Zimbra.|I file esplorati e visualizzati in anteprima non vengono conservati su Zimbra.|Durchsuchte und angezeigte Dateien werden nicht auf Zimbra gespeichert.' ;;
    draft_quota) values='Une pièce jointe ajoutée à un brouillon/message est en revanche stockée par Zimbra et compte dans le quota de la boîte.|An attachment added to a draft/message is stored by Zimbra and counts against the mailbox quota.|Un adjunto añadido a un borrador/mensaje se almacena en Zimbra y cuenta para la cuota del buzón.|Un allegato aggiunto a una bozza/messaggio viene memorizzato da Zimbra e conta nella quota della casella.|Ein zu einem Entwurf/einer Nachricht hinzugefügter Anhang wird von Zimbra gespeichert und auf das Postfachkontingent angerechnet.' ;;
    jdk_missing) values='Erreur : le JDK de Zimbra est introuvable.|Error: the Zimbra JDK was not found.|Error: no se encontró el JDK de Zimbra.|Errore: JDK di Zimbra non trovato.|Fehler: Das Zimbra-JDK wurde nicht gefunden.' ;;
    libraries_missing) values='Erreur : les bibliothèques Zimbra sont absentes de %s|Error: Zimbra libraries are missing from %s|Error: faltan las bibliotecas de Zimbra en %s|Errore: le librerie Zimbra non sono presenti in %s|Fehler: Zimbra-Bibliotheken fehlen in %s' ;;
    compile_jars_missing) values='Erreur : aucun JAR de compilation trouvé dans %s|Error: no compilation JAR found in %s|Error: no se encontró ningún JAR de compilación en %s|Errore: nessun JAR di compilazione trovato in %s|Fehler: Keine JAR-Datei zum Kompilieren in %s gefunden' ;;
    *) values="$1|$1|$1|$1|$1" ;;
  esac
  case "${UI_LANGUAGE:-fr}" in
    fr) field_number=1 ;; en-US) field_number=2 ;; es|es-AR) field_number=3 ;;
    it) field_number=4 ;; de) field_number=5 ;;
    pt-PT|pt-BR|hi-IN|ms-MY) cloud_msg_extended "$1"; return ;;
    ru-RU) cloud_msg_russian "$1"; return ;;
    *) field_number=1 ;;
  esac
  awk -F'|' -v field_number="$field_number" '{print $field_number}' <<<"$values"
}

cloud_msgf() {
  local key="$1"
  shift
  printf "$(cloud_msg "$key")" "$@"
}

# Ask for the privacy-sensitive Unsplash option in one shared implementation.
# CLOUD_UNSPLASH=true|false can be supplied for unattended deployments.
cloud_choose_remote_backgrounds() {
  local current="${1:-false}" default_choice choice
  if [[ "$current" == "true" ]]; then default_choice=1; else default_choice=2; fi

  case "${CLOUD_UNSPLASH:-}" in
    true|TRUE|1|yes|YES) CLOUD_REMOTE_BACKGROUNDS_SELECTION="true"; return ;;
    false|FALSE|0|no|NO) CLOUD_REMOTE_BACKGROUNDS_SELECTION="false"; return ;;
    "") ;;
    *) cloud_msg choose_1_2 >&2; return 1 ;;
  esac

  echo
  cloud_msg remote_backgrounds_prompt
  echo "  1) $(cloud_msg enabled)"
  echo "  2) $(cloud_msg disabled)"
  while true; do
    read -r -p "$(cloud_msg your_choice) [$default_choice] : " choice
	choice="${choice//$'\r'/}"
	choice="${choice#"${choice%%[![:space:]]*}"}"
	choice="${choice%"${choice##*[![:space:]]}"}"
    choice="${choice:-$default_choice}"
    case "$choice" in
      1) CLOUD_REMOTE_BACKGROUNDS_SELECTION="true"; break ;;
      2) CLOUD_REMOTE_BACKGROUNDS_SELECTION="false"; break ;;
      *) cloud_msg choose_1_2 >&2 ;;
    esac
  done
}
