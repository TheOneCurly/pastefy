package de.interaapps.pastefy.controller.pastes;

import de.interaapps.pastefy.Pastefy;
import de.interaapps.pastefy.controller.HttpController;
import de.interaapps.pastefy.exceptions.NotFoundException;
import de.interaapps.pastefy.exceptions.PastePrivateException;
import de.interaapps.pastefy.exceptions.PermissionsDeniedException;
import de.interaapps.pastefy.helper.RequestHelper;
import de.interaapps.pastefy.model.database.*;
import de.interaapps.pastefy.model.database.algorithm.PublicPasteEngagement;
import de.interaapps.pastefy.model.database.algorithm.TagListing;
import de.interaapps.pastefy.model.queryparams.PasteQueryParameters;
import de.interaapps.pastefy.model.requests.paste.AddFriendToPasteRequest;
import de.interaapps.pastefy.model.requests.paste.CreatePasteRequest;
import de.interaapps.pastefy.model.requests.paste.EditPasteRequest;
import de.interaapps.pastefy.model.responses.ActionResponse;
import de.interaapps.pastefy.model.responses.paste.CreatePasteResponse;
import de.interaapps.pastefy.model.responses.paste.PasteResponse;
import de.interaapps.pastefy.services.PasteService;
import org.javawebstack.abstractdata.AbstractElement;
import org.javawebstack.abstractdata.AbstractObject;
import org.javawebstack.http.router.Exchange;
import org.javawebstack.http.router.router.annotation.PathPrefix;
import org.javawebstack.http.router.router.annotation.With;
import org.javawebstack.http.router.router.annotation.params.Attrib;
import org.javawebstack.http.router.router.annotation.params.Body;
import org.javawebstack.http.router.router.annotation.params.Path;
import org.javawebstack.http.router.router.annotation.verbs.Delete;
import org.javawebstack.http.router.router.annotation.verbs.Get;
import org.javawebstack.http.router.router.annotation.verbs.Post;
import org.javawebstack.http.router.router.annotation.verbs.Put;
import org.javawebstack.orm.Repo;
import org.javawebstack.orm.query.Query;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@PathPrefix("/api/v2/paste")
public class PasteController extends HttpController {

    @Post
    @With({"rate-limiter", "auth-login-required-create", "awaiting-access-check", "blocked-check"})
    public CreatePasteResponse create(Exchange exchange, @Body CreatePasteRequest request, @Attrib("user") User user, @Attrib("authkey") AuthKey authKey) {
        if (authKey != null)
            authKey.checkPermission("pastes:create", "pastes:write");


        CreatePasteResponse response = new CreatePasteResponse();

        Paste paste = new Paste();

        Folder folder = Repo.get(Folder.class).where("key", request.folder).first();

        if (user != null) {
            paste.setUserId(user.getId());

            if (folder != null && folder.getUserId().equals(user.getId()))
                paste.setFolder(folder);
        }

        paste.setTitle(request.title);
        paste.setContent(request.content);
        paste.setEncrypted(request.encrypted);
        paste.setType(request.type);

        paste.setVisibility(request.visibility);

        if (request.forkedFrom != null) {
            Paste forkedFrom = Repo.get(Paste.class).where("key", request.forkedFrom).first();
            paste.setForkedFrom(request.forkedFrom);
            if (forkedFrom != null && forkedFrom.isPublic()) {
                Pastefy.getInstance().executeAsync(() -> PublicPasteEngagement.addInterestFromPaste(forkedFrom, 10));
            }
        }


        if (request.expireAt != null && request.expireAt.length() >= 16) {
            paste.setExpireAt(request.expireAt);
        }

        paste.save();


        if (request.tags != null) {
            for (String tag : request.tags) {
                paste.addTag(tag);
            }
        }

        response.success = true;
        response.paste = PasteResponse.create(paste, exchange, user);


        if (request.ai && !request.encrypted && Pastefy.getInstance().aiEnabled()) {
            Pastefy.getInstance().executeAsync(() -> {
                try {
                    AtomicInteger count = new AtomicInteger();
                    AbstractObject aiResponse = Pastefy.getInstance().getPasteAI().generateTags(paste);
                    aiResponse.array("tags")
                            .stream()
                            .map(AbstractElement::string)
                            .filter(t -> request.tags == null || !request.tags.contains(t))
                            .filter(t -> count.incrementAndGet() < 6)
                            .forEach(paste::addTag);

                    if (paste.getType() != Paste.Type.PASTE) return;

                    if ("".equals(paste.getTitle()) && !aiResponse.string("file_name", "").isEmpty()) {
                        paste.setTitle(aiResponse.string("file_name"));
                        paste.save();
                    }
                    if (!paste.getTitle().contains(".") && !aiResponse.string("file_extension", "").isEmpty()) {
                        paste.setTitle(paste.getTitle() + "." + aiResponse.string("file_extension"));
                        paste.save();
                    }
                } catch (Exception ignored) {}
            });
        }

        return response;
    }

    @Get
    public List<PasteResponse> getPastes(Exchange exchange, @Attrib("user") User user, @Attrib("authkey") AuthKey authKey) {
        if (authKey != null)
            authKey.checkPermission("pastes:read");

        Query<Paste> query = Repo.get(Paste.class).query();

        RequestHelper.userIdPastesFilter(user, query, exchange);

        return PasteService.getAllPastes(exchange, PasteQueryParameters.from(exchange));
    }

    @Put("/{id}")
    @With({"auth", "awaiting-access-check", "blocked-check"})
    public ActionResponse putPaste(@Body EditPasteRequest request, @Path("id") String id, @Attrib("user") User user, @Attrib("authkey") AuthKey authKey) {
        if (authKey != null)
            authKey.checkPermission("pastes:edit", "pastes:write");

        ActionResponse response = new ActionResponse();
        Paste paste = Repo.get(Paste.class).where("key", id).first();

        if (paste == null) {
            throw new NotFoundException();
        }

        if ((paste.getUserId() != null && paste.getUserId().equals(user.getId())) || user.type == User.Type.ADMIN) {
            if (request.title != null)
                paste.setTitle(request.title);
            if (request.content != null)
                paste.setContent(request.content);
            if (request.folder != null)
                paste.setFolder(request.folder);
            if (request.type != null)
                paste.setType(request.type);
            if (request.encrypted != null)
                paste.setEncrypted(request.encrypted);
            if (request.visibility != null)
                paste.setVisibility(request.visibility);

            if (request.expireAt != null && request.expireAt.length() >= 16) {
                paste.setExpireAt(request.expireAt);
            }

            if (request.tags != null) {
                List<String> tags = paste.getTags();
                request.tags.stream().filter(t -> !tags.contains(t)).forEach(tag -> {
                    PasteTag pTag = new PasteTag();
                    pTag.paste = paste.getKey();
                    pTag.tag = tag;
                    pTag.save();
                    TagListing.updateCount(pTag.tag);
                });
                tags.stream().filter(t -> !request.tags.contains(t)).forEach(t -> {
                    Repo.get(PasteTag.class).where("paste", paste.getKey()).where("tag", t).delete();
                });
            }

            paste.save();
            response.success = true;
        } else {
            throw new PermissionsDeniedException();
        }
        return response;
    }

    @Get("/{id}")
    @With({"auth-login-required-read", "awaiting-access-check", "blocked-check"})
    public PasteResponse getPaste(Exchange exchange, @Path("id") String id, @Attrib("user") User user) {
        Paste paste = Repo.get(Paste.class).where("key", id).first();

        if (paste == null) {
            throw new NotFoundException();
        }

        if (paste.isPrivate() && (user == null || !Objects.equals(user.id, paste.getUserId()))) {
            throw new PastePrivateException();
        }

        if (paste.isPublic()) {
            if (user == null || !Objects.equals(user.id, paste.getUserId())) {
                Pastefy.getInstance().executeAsync(() ->
                        PublicPasteEngagement.addInterestFromPaste(paste, "true".equalsIgnoreCase(exchange.query("from_frontend", "false")) ? (user == null ? 5 : 4) : 2));
            }
        }

        return PasteResponse.create(paste, exchange, user);
    }

    @Delete("/{id}")
    @With({"auth", "awaiting-access-check", "blocked-check"})
    public ActionResponse deletePaste(Exchange exchange, @Path("id") String id, @Attrib("user") User user, @Attrib("authkey") AuthKey authKey) {
        if (authKey != null)
            authKey.checkPermission("pastes:delete");

        ActionResponse response = new ActionResponse();
        Paste paste = Repo.get(Paste.class).where("key", id).first();

        if (paste == null) {
            throw new NotFoundException();
        }

        if ((paste.getUserId() != null && paste.getUserId().equals(user.getId())) || user.type == User.Type.ADMIN) {
            paste.delete();
            response.success = true;
        } else {
            throw new PermissionsDeniedException();
        }

        return response;
    }

    @Post("/{id}/star")
    @With({"auth-login-required-read", "awaiting-access-check", "blocked-check", "auth"})
    public ActionResponse starPaste(@Path("id") String id, @Attrib("user") User user, @Attrib("authkey") AuthKey authKey) {
        authKey.checkPermission("stars:create");
        Paste paste = Paste.getAccessiblePasteOrFail(id, user);
        user.star(paste);
        return new ActionResponse(true);
    }

    @Delete("/{id}/star")
    @With({"auth-login-required-read", "awaiting-access-check", "blocked-check", "auth"})
    public ActionResponse unstarPaste(@Path("id") String id, @Attrib("user") User user, @Attrib("authkey") AuthKey authKey) {
        authKey.checkPermission("stars:delete");
        Paste paste = Paste.getAccessiblePasteOrFail(id, user);
        user.unstar(paste);
        return new ActionResponse(true);
    }

    @Post("/{id}/friend")
    @Deprecated
    @With({"auth", "awaiting-access-check", "blocked-check"})
    public ActionResponse addFriend(Exchange exchange, @Body AddFriendToPasteRequest request, @Path("id") String id, @Attrib("user") User user, @Attrib("authkey") AuthKey requestAuthKey) {
        throw new RuntimeException("NOT IMPLEMENTED");
    }
}
