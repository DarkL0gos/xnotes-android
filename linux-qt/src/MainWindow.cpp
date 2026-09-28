#include "MainWindow.h"

#include "CanvasWidget.h"

#include <QApplication>
#include <QCloseEvent>
#include <QColorDialog>
#include <QFileDialog>
#include <QFileInfo>
#include <QLabel>
#include <QMessageBox>
#include <QPainter>
#include <QPixmap>
#include <QStatusBar>
#include <QToolBar>

namespace {

struct ToolEntry {
    const char* id;
    const char* label;
    const char* key;
};

const ToolEntry kTools[] = {
    {"pan", "Рука", "1"},
    {"pen", "Перо", "2"},
    {"highlighter", "Маркер", "3"},
    {"eraser", "Ластик", "4"},
    {"lasso", "Лассо", "5"},
};

QString takeError(char* error) {
    QString message = error ? QString::fromUtf8(error) : QStringLiteral("неизвестная ошибка");
    xn_free_string(error);
    return message;
}

}  // namespace

MainWindow::MainWindow() {
    canvas_ = new CanvasWidget(this);
    setCentralWidget(canvas_);
    buildUi();
    connect(canvas_, &CanvasWidget::contentChanged, this, &MainWindow::refresh);
    connect(canvas_, &CanvasWidget::viewChanged, this, &MainWindow::refreshView);
    connect(canvas_, &CanvasWidget::notice, this, [this](int code) {
        statusBar()->showMessage(code == XN_NOTICE_KEEP_ONE_PAGE ? QStringLiteral("В заметке должна остаться хотя бы одна страница")
                                 : code == XN_NOTICE_PAGE_ALREADY_EMPTY ? QStringLiteral("Страница уже пуста")
                                                                        : QString(), 4000);
    });
    resize(1200, 900);
}

MainWindow::~MainWindow() {
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
}

void MainWindow::buildUi() {
    QToolBar* bar = addToolBar(QStringLiteral("Основное"));
    bar->setMovable(false);
    bar->addAction(QStringLiteral("Открыть…"), QKeySequence::Open, this, &MainWindow::chooseOpen);
    bar->addAction(QStringLiteral("Сохранить"), QKeySequence::Save, this, [this] { save(false); });
    bar->addAction(QStringLiteral("Сохранить как…"), QKeySequence::SaveAs, this, [this] { save(true); });
    bar->addSeparator();

    undo_ = bar->addAction(QStringLiteral("↶"), QKeySequence::Undo, this, [this] { if (canvas_->editor()) xn_editor_undo(canvas_->editor()); });
    undo_->setToolTip(QStringLiteral("Отменить (Ctrl+Z)"));
    redo_ = bar->addAction(QStringLiteral("↷"), this, [this] { if (canvas_->editor()) xn_editor_redo(canvas_->editor()); });
    redo_->setShortcuts({QKeySequence(Qt::CTRL | Qt::SHIFT | Qt::Key_Z), QKeySequence(Qt::CTRL | Qt::Key_Y)});
    redo_->setToolTip(QStringLiteral("Повторить (Ctrl+Shift+Z)"));
    bar->addSeparator();

    tools_ = new QActionGroup(this);
    for (const ToolEntry& t : kTools) {
        QAction* a = bar->addAction(QString::fromUtf8(t.label));
        a->setCheckable(true);
        a->setShortcut(QKeySequence(QString::fromLatin1(t.key)));
        a->setData(QByteArray(t.id));
        tools_->addAction(a);
        if (qstrcmp(t.id, "pen") == 0) a->setChecked(true);
    }
    connect(tools_, &QActionGroup::triggered, this, [this](QAction* a) { canvas_->setTool(a->data().toByteArray().constData()); });
    colorAction_ = bar->addAction(QStringLiteral("Цвет"), this, &MainWindow::chooseColor);
    bar->addSeparator();

    bar->addAction(QStringLiteral("←"), QKeySequence(Qt::Key_PageUp), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::max(0, xn_editor_current_page(e) - 1));
    });
    pageLabel_ = new QLabel(this);
    pageLabel_->setMinimumWidth(60);
    pageLabel_->setAlignment(Qt::AlignCenter);
    bar->addWidget(pageLabel_);
    bar->addAction(QStringLiteral("→"), QKeySequence(Qt::Key_PageDown), this, [this] {
        if (auto* e = canvas_->editor()) xn_editor_go_to_page(e, std::min(xn_note_page_count(note_) - 1, xn_editor_current_page(e) + 1));
    });
    bar->addAction(QStringLiteral("−"), QKeySequence::ZoomOut, this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 0); });
    zoomLabel_ = new QLabel(this);
    zoomLabel_->setMinimumWidth(50);
    zoomLabel_->setAlignment(Qt::AlignCenter);
    bar->addWidget(zoomLabel_);
    bar->addAction(QStringLiteral("+"), QKeySequence(Qt::CTRL | Qt::Key_Equal), this, [this] { if (auto* e = canvas_->editor()) xn_editor_zoom_step(e, 1); });
    bar->addAction(QStringLiteral("По ширине"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_fit_width(e); });
    bar->addAction(QStringLiteral("+ Страница"), this, [this] { if (auto* e = canvas_->editor()) xn_editor_add_page(e); });
    bar->addAction(QStringLiteral("− Страница"), this, [this] {
        if (!canvas_->editor()) return;
        if (QMessageBox::question(this, QStringLiteral("Удалить страницу"), QStringLiteral("Удалить текущую страницу?")) == QMessageBox::Yes)
            xn_editor_delete_current_page(canvas_->editor());
    });
    bar->addSeparator();
    QAction* dark = bar->addAction(QStringLiteral("Тёмная"));
    dark->setCheckable(true);
    dark->setChecked(dark_);
    connect(dark, &QAction::toggled, this, [this](bool on) {
        dark_ = on;
        canvas_->setDark(on);
    });

    // Canvas commands without buttons.
    auto bind = [this](const QKeySequence& key, auto fn) {
        auto* a = new QAction(this);
        a->setShortcut(key);
        connect(a, &QAction::triggered, this, fn);
        addAction(a);
    };
    bind(QKeySequence(Qt::Key_Escape), [this] { if (canvas_->editor()) xn_editor_escape(canvas_->editor()); });
    bind(QKeySequence(Qt::Key_Delete), [this] { if (canvas_->editor()) xn_editor_delete_selection(canvas_->editor()); });
    bind(QKeySequence::New, [this] { if (confirmDiscard()) newNote(); });
    bind(QKeySequence::Quit, [this] { close(); });
    setInk(QColor(0x21, 0x96, 0xf3));
}

void MainWindow::install(xn_note* note, std::unique_ptr<QTemporaryDir> workDir, const QString& path) {
    canvas_->setNote(nullptr);
    if (note_) xn_note_close(note_);
    note_ = note;
    workDir_ = std::move(workDir);
    path_ = path;
    canvas_->setNote(note_);
    canvas_->setDark(dark_);
    if (QAction* tool = tools_->checkedAction()) canvas_->setTool(tool->data().toByteArray().constData());
    if (colorAction_->data().isValid()) setInk(colorAction_->data().value<QColor>());
    refresh();
    refreshView();
}

void MainWindow::newNote() { install(xn_note_new(1), nullptr, QString()); }

bool MainWindow::open(const QString& path) {
    auto workDir = std::make_unique<QTemporaryDir>();
    if (!workDir->isValid()) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось создать временную папку"));
        return false;
    }
    char* error = nullptr;
    const QByteArray file = QFile::encodeName(QFileInfo(path).absoluteFilePath());
    const QByteArray dir = QFile::encodeName(workDir->path());
    xn_note* note = xn_note_open(file.constData(), dir.constData(), canvas_->host(), &error);
    if (!note) {
        QMessageBox::warning(this, QStringLiteral("Открыть"), QStringLiteral("Не удалось открыть %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    install(note, std::move(workDir), QFileInfo(path).absoluteFilePath());
    return true;
}

void MainWindow::chooseOpen() {
    if (!confirmDiscard()) return;
    const QString path = QFileDialog::getOpenFileName(this, QStringLiteral("Открыть заметку"), QFileInfo(path_).absolutePath(),
                                                      QStringLiteral("Заметки xnotes (*.xnote)"));
    if (!path.isEmpty()) open(path);
}

bool MainWindow::save(bool askPath) {
    if (!note_) return false;
    QString path = path_;
    if (askPath || path.isEmpty()) {
        path = QFileDialog::getSaveFileName(this, QStringLiteral("Сохранить заметку"), path.isEmpty() ? QStringLiteral("note.xnote") : path,
                                            QStringLiteral("Заметки xnotes (*.xnote)"));
        if (path.isEmpty()) return false;
        if (!path.endsWith(QLatin1String(".xnote"))) path += QLatin1String(".xnote");
    }
    char* error = nullptr;
    if (!xn_note_save(note_, QFile::encodeName(path).constData(), canvas_->host(), &error)) {
        QMessageBox::warning(this, QStringLiteral("Сохранить"), QStringLiteral("Не удалось сохранить %1:\n%2").arg(path, takeError(error)));
        return false;
    }
    path_ = path;
    statusBar()->showMessage(QStringLiteral("Сохранено: %1").arg(path), 3000);
    refresh();
    return true;
}

bool MainWindow::confirmDiscard() {
    if (!note_ || !xn_note_is_dirty(note_)) return true;
    const auto answer = QMessageBox::question(this, QStringLiteral("Несохранённые изменения"),
                                              QStringLiteral("Сохранить изменения в заметке?"),
                                              QMessageBox::Save | QMessageBox::Discard | QMessageBox::Cancel);
    if (answer == QMessageBox::Save) return save(false);
    return answer == QMessageBox::Discard;
}

void MainWindow::closeEvent(QCloseEvent* e) {
    if (confirmDiscard()) e->accept(); else e->ignore();
}

void MainWindow::chooseColor() {
    const QColor c = QColorDialog::getColor(colorAction_->data().value<QColor>(), this, QStringLiteral("Цвет чернил"));
    if (c.isValid()) setInk(c);
}

void MainWindow::setInk(const QColor& c) {
    colorAction_->setData(c);
    QPixmap swatch(16, 16);
    swatch.fill(c);
    colorAction_->setIcon(QIcon(swatch));
    if (auto* e = canvas_->editor())
        xn_editor_set_ink_color(e, (xn_rgba(c.red()) << 24) | (xn_rgba(c.green()) << 16) | (xn_rgba(c.blue()) << 8) | 0xFF);
}

void MainWindow::refresh() {
    auto* e = canvas_->editor();
    undo_->setEnabled(e && xn_editor_can_undo(e));
    redo_->setEnabled(e && xn_editor_can_redo(e));
    const QString name = path_.isEmpty() ? QStringLiteral("Новая заметка") : QFileInfo(path_).fileName();
    const bool dirty = note_ && xn_note_is_dirty(note_);
    setWindowTitle((dirty ? QStringLiteral("• ") : QString()) + name + QStringLiteral(" — xnotes"));
    refreshView();
}

void MainWindow::refreshView() {
    auto* e = canvas_->editor();
    if (!e || !note_) return;
    pageLabel_->setText(QStringLiteral("%1 / %2").arg(xn_editor_current_page(e) + 1).arg(xn_note_page_count(note_)));
    zoomLabel_->setText(QStringLiteral("%1%").arg(int(xn_editor_zoom(e) * 100)));
}
